package bbbbot.storage;

import bbbbot.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStoreTest {

    @TempDir
    Path tmp;

    private Path root;
    private FakeBucket bucket;
    private FileStore store;

    @BeforeEach
    void setUp() {
        root = tmp.resolve("recordings");
        AppProperties props = new AppProperties();
        props.getStorage().setRootDir(root.toString());
        props.getStorage().getS3().setPrefix("/recorder/");
        bucket = new FakeBucket();
        store = new FileStore(props, bucket);
    }

    @Test
    void schluesselIstPfadRelativZumWurzelverzeichnisMitPraefix() {
        UUID id = UUID.randomUUID();
        assertThat(store.keyFor(root.resolve(id + "/segment_000.mp3")))
                .isEqualTo("recorder/" + id + "/segment_000.mp3");
        assertThat(store.keyFor(root.resolve(id + "/documents/a.pdf")))
                .isEqualTo("recorder/" + id + "/documents/a.pdf");
    }

    @Test
    void alterPfadAusserhalbDesWurzelverzeichnissesZaehltAbDerAufnahmeKennung() {
        UUID id = UUID.randomUUID();
        Path old = Path.of("/var/lib/old-place/" + id + "/meeting.mp4");
        assertThat(store.keyFor(old)).isEqualTo("recorder/" + id + "/meeting.mp4");
    }

    @Test
    void publishLaedtHochUndEnsureLocalHoltNachLoeschenZurueck() throws IOException {
        Path file = write(UUID.randomUUID() + "/segment_000.mp3", "audio");
        assertThat(store.publish(file)).isTrue();
        assertThat(bucket.objects).containsKey(store.keyFor(file));

        Files.delete(file);
        assertThat(store.ensureLocal(file)).isTrue();
        assertThat(Files.readString(file)).isEqualTo("audio");
        // Zeitstempel des Originals, damit FullAudioService Aktualitaet vergleichen kann
        assertThat(Files.getLastModifiedTime(file).toInstant()).isEqualTo(bucket.modified.get(store.keyFor(file)));
        // kein Teil-Download liegen geblieben
        try (var s = Files.list(file.getParent())) {
            assertThat(s.map(p -> p.getFileName().toString())).containsExactly("segment_000.mp3");
        }
    }

    @Test
    void ensureLocalOhneObjektMeldetFalse() {
        assertThat(store.ensureLocal(root.resolve(UUID.randomUUID() + "/fehlt.mp3"))).isFalse();
    }

    @Test
    void deleteDirectoryLoeschtLokalUndImBucket() throws IOException {
        UUID id = UUID.randomUUID();
        Path a = write(id + "/a.mp3", "a");
        Path b = write(id + "/documents/b.pdf", "b");
        Path other = write(UUID.randomUUID() + "/c.mp3", "c");
        store.publish(a);
        store.publish(b);
        store.publish(other);

        assertThat(store.deleteDirectory(root.resolve(id.toString()))).isTrue();

        assertThat(Files.exists(root.resolve(id.toString()))).isFalse();
        assertThat(bucket.objects.keySet()).containsExactly(store.keyFor(other));
    }

    @Test
    void ausliefernAusDemBucketMitRange() {
        Path file = root.resolve(UUID.randomUUID() + "/meeting.mp4");
        String key = store.keyFor(file);
        bucket.put(key, "0123456789");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(HttpHeaders.RANGE, "bytes=2-5");
        ResponseEntity<Resource> response = store.serveRemote(key, bucket.info(key),
                MediaType.parseMediaType("video/mp4"), "inline", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-5/10");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(4);
        assertThat(body(response)).isEqualTo("2345");
    }

    @Test
    void ausliefernAusDemBucketOhneRangeUndOffenerBereich() {
        Path file = root.resolve(UUID.randomUUID() + "/meeting.mp4");
        String key = store.keyFor(file);
        bucket.put(key, "0123456789");

        ResponseEntity<Resource> full = store.serveRemote(key, bucket.info(key), MediaType.TEXT_PLAIN, "inline",
                new MockHttpServletRequest("GET", "/x"));
        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(full.getHeaders().getContentLength()).isEqualTo(10);
        assertThat(full.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(body(full)).isEqualTo("0123456789");

        MockHttpServletRequest tail = new MockHttpServletRequest("GET", "/x");
        tail.addHeader(HttpHeaders.RANGE, "bytes=7-");
        assertThat(body(store.serveRemote(key, bucket.info(key), MediaType.TEXT_PLAIN, "inline", tail)))
                .isEqualTo("789");
    }

    @Test
    void ungueltigerBereichErgibt416() {
        Path file = root.resolve(UUID.randomUUID() + "/meeting.mp4");
        String key = store.keyFor(file);
        bucket.put(key, "0123456789");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(HttpHeaders.RANGE, "bytes=20-30");

        ResponseEntity<Resource> response = store.serveRemote(key, bucket.info(key), MediaType.TEXT_PLAIN,
                "inline", request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */10");
    }

    @Test
    void lokalVorhandeneDateiWirdDirektAusgeliefert() throws IOException {
        Path file = write(UUID.randomUUID() + "/meeting.mp4", "lokal");
        ResponseEntity<Resource> response = store.serve(file, MediaType.TEXT_PLAIN, "inline");
        assertThat(response.getBody()).isInstanceOf(FileSystemResource.class);
        assertThat(bucket.headCalls).isZero();
    }

    @Test
    void nirgendsVorhandeneDateiErgibt404() {
        assertThatThrownBy(() -> store.serve(root.resolve(UUID.randomUUID() + "/x.mp4"),
                MediaType.TEXT_PLAIN, "inline"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void raeumungEntferntNurGesicherteUndLangeUngenutzteDateien() throws IOException {
        UUID id = UUID.randomUUID();
        Path saved = write(id + "/segment_000.mp3", "gesichert");
        Path unsaved = write(id + "/capture.webm", "nur lokal");
        Path derived = write(id + "/audio.mp3", "abgeleitet");
        Path fresh = write(id + "/segment_001.mp3", "frisch");
        store.publish(saved);
        store.publish(fresh);

        FileTime old = FileTime.from(Instant.now().minus(3, ChronoUnit.DAYS));
        for (Path p : List.of(saved, unsaved, derived)) Files.setLastModifiedTime(p, old);
        // publish() merkt sich die Nutzung - fuer den Test zuruecksetzen
        AppProperties props = new AppProperties();
        props.getStorage().setRootDir(root.toString());
        props.getStorage().getS3().setPrefix("recorder");
        FileStore restarted = new FileStore(props, bucket);

        restarted.evictCache();

        assertThat(Files.exists(saved)).isFalse();
        assertThat(Files.exists(derived)).isFalse();
        assertThat(Files.exists(unsaved)).as("nie gesichert -> bleibt").isTrue();
        assertThat(Files.exists(fresh)).as("kuerzlich geschrieben -> bleibt").isTrue();
    }

    @Test
    void findByPrefixSuchtAuchImBucket() {
        UUID id = UUID.randomUUID();
        Path dir = root.resolve(id.toString());
        bucket.put(store.keyFor(dir.resolve("capture.mp4")), "x");
        bucket.put(store.keyFor(dir.resolve("documents/capture.txt")), "y");

        Optional<Path> found = store.findByPrefix(dir, "capture.");

        assertThat(found).contains(dir.resolve("capture.mp4").toAbsolutePath().normalize());
    }

    @Test
    void ohneAblageIstPublishEinNoOp() throws IOException {
        AppProperties props = new AppProperties();
        props.getStorage().setRootDir(root.toString());
        FileStore local = new FileStore(props, (ObjectStore) null);
        Path file = write(UUID.randomUUID() + "/a.mp3", "a");

        assertThat(local.publish(file)).isTrue();
        assertThat(local.isRemote()).isFalse();
        assertThat(FileStore.isRemoteMode()).isFalse();
        assertThat(FileStore.probablyExists(file.toString())).isTrue();
        assertThat(FileStore.probablyExists(root.resolve("fehlt.mp3").toString())).isFalse();
    }

    private Path write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static String body(ResponseEntity<Resource> response) {
        try (InputStream in = response.getBody().getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Bucket im Speicher. */
    static class FakeBucket implements ObjectStore {
        final Map<String, byte[]> objects = new TreeMap<>();
        final Map<String, Instant> modified = new TreeMap<>();
        int headCalls;

        void put(String key, String content) {
            objects.put(key, content.getBytes(StandardCharsets.UTF_8));
            modified.put(key, Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS));
        }

        Info info(String key) {
            return new Info(objects.get(key).length, modified.get(key));
        }

        @Override
        public Optional<Info> head(String key) {
            headCalls++;
            return objects.containsKey(key) ? Optional.of(info(key)) : Optional.empty();
        }

        @Override
        public void upload(Path file, String key) throws IOException {
            objects.put(key, Files.readAllBytes(file));
            modified.put(key, Instant.now().truncatedTo(ChronoUnit.SECONDS));
        }

        @Override
        public void download(String key, Path target) throws IOException {
            Files.write(target, objects.get(key));
        }

        @Override
        public InputStream open(String key, Long start, Long endInclusive) {
            byte[] data = objects.get(key);
            int from = start == null ? 0 : start.intValue();
            int to = endInclusive == null ? data.length - 1 : endInclusive.intValue();
            return new ByteArrayInputStream(data, from, to - from + 1);
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }

        @Override
        public int deletePrefix(String prefix) {
            List<String> keys = list(prefix);
            keys.forEach(objects::remove);
            return keys.size();
        }

        @Override
        public List<String> list(String prefix) {
            List<String> keys = new ArrayList<>();
            for (String k : objects.keySet()) if (k.startsWith(prefix)) keys.add(k);
            return keys;
        }

        @Override
        public void check() {
        }

        @Override
        public String describe() {
            return "fake://bucket";
        }
    }
}
