package bbbbot.storage;

import bbbbot.config.AppProperties;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Ablage der Aufnahme-Dateien.
 *
 * <p>Die Anwendung arbeitet weiter mit lokalen Pfaden unter {@code rootDir} -
 * ffmpeg, Whisper und Tika brauchen echte Dateien. Mit {@code backend=s3} ist
 * dieses Verzeichnis aber nur noch Arbeitsplatz und Zwischenspeicher:
 * <ul>
 *   <li>{@link #publish(Path)} sichert eine fertige Datei in den Bucket,</li>
 *   <li>{@link #ensureLocal(Path)} holt sie zurueck, wenn sie lokal fehlt
 *       (z.B. nach einem Neustart auf frischem Speicher),</li>
 *   <li>{@link #serve} liefert sie aus - aus dem Zwischenspeicher oder direkt
 *       aus dem Bucket, mit Range-Requests zum Springen in Audio und Video,</li>
 *   <li>{@link #evictCache()} entfernt lange ungenutzte, gesicherte Kopien.</li>
 * </ul>
 * Der Objektschluessel ist der Pfad relativ zu {@code rootDir} (plus Praefix).
 * Die Datenbank behaelt ihre Pfade; ein Wechsel von local auf s3 braucht nur ein
 * einmaliges Hochladen des Verzeichnisses (siehe DOCKERHUB.md).
 *
 * <p>Ohne S3 sind alle Operationen das, was der Code vorher direkt tat.
 */
@Service
public class FileStore {

    private static final Logger log = LoggerFactory.getLogger(FileStore.class);

    /** Erzeugte Dateien, die nie gesichert, sondern bei Bedarf neu gebaut werden. */
    private static final Set<String> DERIVED_FILES = Set.of("audio.mp3");

    /** Unterverzeichnisse, die nur lokal leben (Bot-Videoaufzeichnung). */
    private static final Set<String> LOCAL_ONLY_DIRS = Set.of("video-tmp");

    private static final int UPLOAD_ATTEMPTS = 3;

    /** Fuer Stellen ohne Zugriff auf die Bean (Ansichts-DTOs). */
    private static volatile boolean remoteMode;

    private final Path root;
    private final ObjectStore remote;
    private final String prefix;
    private final Duration cacheTtl;

    private final Map<Path, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** Letzte Nutzung je Datei - fuer die Raeumung des Zwischenspeichers. */
    private final Map<Path, Instant> lastUsed = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public FileStore(AppProperties props, ObjectProvider<ObjectStore> remoteProvider) {
        this(props, remoteProvider.getIfAvailable());
    }

    /** Direkt mit (oder ohne, {@code null}) Ablage - fuer Tests und Werkzeuge. */
    public FileStore(AppProperties props, ObjectStore remote) {
        AppProperties.Storage storage = props.getStorage();
        this.root = Path.of(storage.getRootDir()).toAbsolutePath().normalize();
        this.remote = remote;
        this.prefix = normalizePrefix(storage.getS3() == null ? null : storage.getS3().getPrefix());
        this.cacheTtl = Duration.ofHours(Math.max(1, storage.getCacheHours()));
        remoteMode = remote != null;
        if (remote != null) {
            try {
                remote.check();
                log.info("Ablage: {} (lokaler Zwischenspeicher {}, {} h)", remote.describe(), root,
                        cacheTtl.toHours());
            } catch (IOException e) {
                // Nicht abbrechen: Der Bucket kann kurz nach dem Start erreichbar
                // werden; die einzelnen Operationen melden Fehler selbst.
                log.error("Ablage {} beim Start nicht erreichbar: {}", remote.describe(), e.getMessage());
            }
        } else {
            log.info("Ablage: lokal unter {}", root);
        }
    }

    /** True, wenn Dateien in einem Bucket liegen (und lokal fehlen duerfen). */
    public static boolean isRemoteMode() {
        return remoteMode;
    }

    public boolean isRemote() {
        return remote != null;
    }

    public Path root() {
        return root;
    }

    /** Verzeichnis einer Aufnahme (wird bei Bedarf angelegt). */
    public Path recordingDir(UUID recordingId) throws IOException {
        Path dir = root.resolve(recordingId.toString());
        Files.createDirectories(dir);
        return dir;
    }

    // ------------------------------------------------------------------
    // Lesen
    // ------------------------------------------------------------------

    /**
     * Sorgt dafuer, dass die Datei lokal liegt.
     *
     * @return true, wenn sie danach lokal lesbar ist
     */
    public boolean ensureLocal(Path file) {
        if (file == null) return false;
        Path abs = file.toAbsolutePath().normalize();
        if (Files.isRegularFile(abs)) {
            touch(abs);
            return true;
        }
        if (remote == null) return false;
        ReentrantLock lock = locks.computeIfAbsent(abs, p -> new ReentrantLock());
        lock.lock();
        try {
            if (Files.isRegularFile(abs)) {
                touch(abs);
                return true;
            }
            String key = keyFor(abs);
            Optional<ObjectStore.Info> info = remote.head(key);
            if (info.isEmpty()) return false;
            Files.createDirectories(abs.getParent());
            Path tmp = abs.resolveSibling("." + abs.getFileName() + ".part-" + UUID.randomUUID());
            try {
                long started = System.currentTimeMillis();
                remote.download(key, tmp);
                // Zeitstempel des Originals: FullAudioService vergleicht ihn,
                // um eine veraltete zusammengefuegte Tonspur zu erkennen.
                Files.setLastModifiedTime(tmp, FileTime.from(info.get().lastModified()));
                Files.move(tmp, abs, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                log.info("Aus der Ablage geholt: {} ({} Byte, {} ms)", key, info.get().size(),
                        System.currentTimeMillis() - started);
            } finally {
                Files.deleteIfExists(tmp);
            }
            touch(abs);
            return true;
        } catch (IOException e) {
            log.warn("Datei {} konnte nicht aus der Ablage geholt werden: {}", abs, e.getMessage());
            return false;
        } finally {
            lock.unlock();
            locks.remove(abs, lock);
        }
    }

    /** Existiert die Datei lokal oder in der Ablage? (fragt den Bucket ggf. an) */
    public boolean exists(Path file) {
        if (file == null) return false;
        Path abs = file.toAbsolutePath().normalize();
        if (Files.isRegularFile(abs)) return true;
        if (remote == null) return false;
        try {
            return remote.head(keyFor(abs)).isPresent();
        } catch (IOException e) {
            log.warn("Ablage nicht abfragbar fuer {}: {}", abs, e.getMessage());
            return false;
        }
    }

    /**
     * Schnelle Pruefung ohne Netzwerk: lokal vorhanden oder - mit Ablage - als
     * vorhanden angenommen. Fuer Listen, die sonst je Eintrag den Bucket fragten.
     */
    public static boolean probablyExists(String path) {
        if (path == null || path.isBlank()) return false;
        return remoteMode || Files.exists(Path.of(path));
    }

    /** Groesse lokal oder aus der Ablage; 0, wenn nirgends vorhanden. */
    public long size(Path file) {
        if (file == null) return 0;
        Path abs = file.toAbsolutePath().normalize();
        try {
            if (Files.isRegularFile(abs)) return Files.size(abs);
            if (remote == null) return 0;
            return remote.head(keyFor(abs)).map(ObjectStore.Info::size).orElse(0L);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Sucht im Verzeichnis eine Datei, deren Name so beginnt - lokal und in der
     * Ablage. Fuer Dateien, deren Endung erst zur Laufzeit feststand.
     */
    public Optional<Path> findByPrefix(Path dir, String namePrefix) {
        Path absDir = dir.toAbsolutePath().normalize();
        try (var stream = Files.list(absDir)) {
            Optional<Path> local = stream
                    .filter(p -> p.getFileName().toString().startsWith(namePrefix))
                    .filter(Files::isRegularFile)
                    .findFirst();
            if (local.isPresent()) return local;
        } catch (IOException ignored) {
            // Verzeichnis gibt es lokal (noch) nicht
        }
        if (remote == null) return Optional.empty();
        String dirKey = keyFor(absDir) + "/";
        try {
            return remote.list(dirKey + namePrefix).stream()
                    .map(k -> k.substring(dirKey.length()))
                    .filter(name -> !name.contains("/"))
                    .findFirst()
                    .map(absDir::resolve);
        } catch (IOException e) {
            log.warn("Ablage nicht durchsuchbar unter {}: {}", dirKey, e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // Schreiben und Loeschen
    // ------------------------------------------------------------------

    /**
     * Sichert eine fertig geschriebene Datei in der Ablage. Ohne Ablage ein
     * No-op. Fehler werden protokolliert und nicht geworfen: Die Datei bleibt
     * lokal liegen und wird nicht geraeumt, solange sie nicht gesichert ist.
     *
     * @return true, wenn die Datei danach in der Ablage liegt (oder keine genutzt wird)
     */
    public boolean publish(Path file) {
        if (remote == null || file == null) return true;
        Path abs = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(abs)) {
            log.warn("Nicht gesichert, Datei fehlt: {}", abs);
            return false;
        }
        String key = keyFor(abs);
        for (int attempt = 1; attempt <= UPLOAD_ATTEMPTS; attempt++) {
            try {
                long started = System.currentTimeMillis();
                remote.upload(abs, key);
                touch(abs);
                log.debug("Gesichert: {} ({} ms)", key, System.currentTimeMillis() - started);
                return true;
            } catch (IOException e) {
                log.warn("Sichern von {} fehlgeschlagen (Versuch {}/{}): {}", key, attempt, UPLOAD_ATTEMPTS,
                        e.getMessage());
                if (attempt < UPLOAD_ATTEMPTS) sleepQuietly(1000L * attempt * attempt);
            }
        }
        log.error("Datei {} konnte nicht in der Ablage gesichert werden - liegt nur lokal", abs);
        return false;
    }

    /** Schreibt Text und sichert die Datei (legt das Verzeichnis bei Bedarf an). */
    public void writeString(Path file, String content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, content, java.nio.charset.StandardCharsets.UTF_8);
        publish(file);
    }

    /** Loescht die Datei lokal und in der Ablage. */
    public void delete(Path file) {
        if (file == null) return;
        Path abs = file.toAbsolutePath().normalize();
        try {
            Files.deleteIfExists(abs);
        } catch (IOException e) {
            log.warn("Datei {} konnte nicht geloescht werden: {}", abs, e.getMessage());
        }
        lastUsed.remove(abs);
        if (remote == null) return;
        try {
            remote.delete(keyFor(abs));
        } catch (IOException e) {
            log.warn("Datei {} konnte in der Ablage nicht geloescht werden: {}", abs, e.getMessage());
        }
    }

    /**
     * Loescht ein Verzeichnis samt Inhalt lokal und in der Ablage.
     *
     * @return false, wenn etwas nicht geloescht werden konnte
     */
    public boolean deleteDirectory(Path dir) {
        if (dir == null) return true;
        Path abs = dir.toAbsolutePath().normalize();
        boolean ok = true;
        if (Files.exists(abs)) {
            try (var stream = Files.walk(abs)) {
                for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                    try {
                        Files.delete(p);
                        lastUsed.remove(p);
                    } catch (IOException e) {
                        ok = false;
                    }
                }
            } catch (IOException e) {
                log.warn("Verzeichnis {} konnte nicht geloescht werden: {}", abs, e.getMessage());
                ok = false;
            }
        }
        if (remote != null && !abs.equals(root)) {
            try {
                int removed = remote.deletePrefix(keyFor(abs) + "/");
                if (removed > 0) log.info("{} Datei(en) unter {} aus der Ablage geloescht", removed, keyFor(abs));
            } catch (IOException e) {
                log.warn("Verzeichnis {} konnte in der Ablage nicht geloescht werden: {}", abs, e.getMessage());
                ok = false;
            }
        }
        return ok;
    }

    // ------------------------------------------------------------------
    // Ausliefern
    // ------------------------------------------------------------------

    /**
     * Antwort mit dem Inhalt der Datei. Lokal uebernimmt Spring die
     * Range-Requests; aus dem Bucket wird nur der angefragte Ausschnitt geholt -
     * so springt ein Player in einem grossen Video, ohne dass die ganze Datei
     * erst geladen werden muss.
     *
     * @throws ResponseStatusException 404, wenn die Datei nirgends liegt
     */
    public ResponseEntity<Resource> serve(Path file, MediaType type, String contentDisposition) {
        if (file == null) throw notFound();
        Path abs = file.toAbsolutePath().normalize();
        if (Files.isRegularFile(abs)) {
            touch(abs);
            return ResponseEntity.ok()
                    .contentType(type)
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                    .body(new FileSystemResource(abs));
        }
        if (remote == null) throw notFound();
        String key = keyFor(abs);
        ObjectStore.Info info;
        try {
            info = remote.head(key).orElseThrow(FileStore::notFound);
        } catch (IOException e) {
            log.warn("Ablage nicht abfragbar fuer {}: {}", key, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Ablage nicht erreichbar");
        }
        return serveRemote(key, info, type, contentDisposition, currentRequest());
    }

    ResponseEntity<Resource> serveRemote(String key, ObjectStore.Info info, MediaType type,
                                         String contentDisposition, HttpServletRequest request) {
        long length = info.size();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setLastModified(info.lastModified());
        boolean head = request != null && "HEAD".equalsIgnoreCase(request.getMethod());
        String rangeHeader = request == null ? null : request.getHeader(HttpHeaders.RANGE);

        Long start = null;
        Long end = null;
        if (rangeHeader != null && !rangeHeader.isBlank() && length > 0) {
            try {
                List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
                if (!ranges.isEmpty()) {
                    // Mehrteilige Bereiche fragen Player nicht an; der erste genuegt.
                    HttpRange range = ranges.get(0);
                    start = range.getRangeStart(length);
                    end = range.getRangeEnd(length);
                    if (start >= length || end < start) throw new IllegalArgumentException("Bereich ausserhalb");
                }
            } catch (IllegalArgumentException e) {
                headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).headers(headers).build();
            }
        }

        try {
            if (start != null) {
                headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
                headers.setContentLength(end - start + 1);
                InputStream body = head ? new ByteArrayInputStream(new byte[0]) : remote.open(key, start, end);
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers)
                        .body(new InputStreamResource(body));
            }
            headers.setContentLength(length);
            InputStream body = head ? new ByteArrayInputStream(new byte[0]) : remote.open(key, null, null);
            return ResponseEntity.ok().headers(headers).body(new InputStreamResource(body));
        } catch (IOException e) {
            log.warn("Ablage nicht lesbar fuer {}: {}", key, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Ablage nicht erreichbar");
        }
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Datei nicht vorhanden");
    }

    // ------------------------------------------------------------------
    // Zwischenspeicher
    // ------------------------------------------------------------------

    /**
     * Entfernt lokale Kopien, die laenger als {@code cacheHours} unbenutzt sind -
     * aber nur, wenn sie nachweislich (gleiche Groesse) im Bucket liegen. Was
     * nie gesichert wurde (laufende Aufnahmen, gescheiterter Upload), bleibt.
     */
    @Scheduled(fixedDelay = 30 * 60_000L, initialDelay = 10 * 60_000L)
    public void evictCache() {
        if (remote == null || !Files.isDirectory(root)) return;
        Instant cutoff = Instant.now().minus(cacheTtl);
        Instant partCutoff = Instant.now().minus(Duration.ofHours(1));
        int removed = 0;
        long bytes = 0;
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !isLocalOnly(p))
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("Zwischenspeicher {} nicht durchsuchbar: {}", root, e.getMessage());
            return;
        }
        for (Path file : files) {
            try {
                Instant used = lastUse(file);
                String name = file.getFileName().toString();
                if (name.startsWith(".") && name.contains(".part-")) {
                    // Rest eines abgebrochenen Downloads
                    if (used.isBefore(partCutoff)) Files.deleteIfExists(file);
                    continue;
                }
                if (used.isAfter(cutoff)) continue;
                long size = Files.size(file);
                boolean safe = DERIVED_FILES.contains(name)
                        || remote.head(keyFor(file)).map(i -> i.size() == size).orElse(false);
                if (!safe) continue;
                Files.deleteIfExists(file);
                lastUsed.remove(file);
                removed++;
                bytes += size;
            } catch (IOException e) {
                log.debug("Zwischenspeicher: {} uebersprungen: {}", file, e.getMessage());
            }
        }
        deleteEmptyDirectories();
        if (removed > 0) {
            log.info("Zwischenspeicher: {} Datei(en) ({} MB) geraeumt, liegen weiter in {}", removed,
                    bytes / (1024 * 1024), remote.describe());
        }
    }

    private boolean isLocalOnly(Path file) {
        Path rel = root.relativize(file.toAbsolutePath().normalize());
        return rel.getNameCount() > 1 && LOCAL_ONLY_DIRS.contains(rel.getName(0).toString());
    }

    private Instant lastUse(Path file) throws IOException {
        Instant modified = Files.getLastModifiedTime(file).toInstant();
        Instant used = lastUsed.get(file);
        return used != null && used.isAfter(modified) ? used : modified;
    }

    private void deleteEmptyDirectories() {
        try (var stream = Files.walk(root)) {
            for (Path dir : stream.filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())
                    .filter(d -> !d.equals(root))
                    .toList()) {
                try (var children = Files.list(dir)) {
                    if (children.findAny().isEmpty()) Files.deleteIfExists(dir);
                } catch (IOException ignored) {
                    // belegt oder schon weg
                }
            }
        } catch (IOException ignored) {
            // nicht kritisch
        }
    }

    private void touch(Path abs) {
        lastUsed.put(abs, Instant.now());
    }

    // ------------------------------------------------------------------
    // Schluessel
    // ------------------------------------------------------------------

    /**
     * Objektschluessel einer Datei: Pfad relativ zu rootDir. Liegt ein (alter)
     * Pfad ausserhalb - etwa weil rootDir verschoben wurde -, zaehlt alles ab dem
     * Aufnahme-Verzeichnis (erste UUID im Pfad).
     */
    String keyFor(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path rel;
        if (abs.startsWith(root)) {
            rel = root.relativize(abs);
        } else {
            rel = abs.getFileName();
            for (int i = 0; i < abs.getNameCount(); i++) {
                if (isUuid(abs.getName(i).toString())) {
                    rel = abs.subpath(i, abs.getNameCount());
                    break;
                }
            }
        }
        StringBuilder key = new StringBuilder(prefix);
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (i > 0) key.append('/');
            key.append(rel.getName(i));
        }
        return key.toString();
    }

    private static boolean isUuid(String s) {
        if (s.length() != 36) return false;
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String normalizePrefix(String prefix) {
        if (prefix == null) return "";
        String p = prefix.trim().replaceAll("^/+", "").replaceAll("/+$", "");
        return p.isEmpty() ? "" : p + "/";
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void close() {
        if (remote instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // beim Herunterfahren egal
            }
        }
    }
}
