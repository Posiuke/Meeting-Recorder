package bbbbot.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Entfernter Objektspeicher (S3 oder S3-kompatibel). Bewusst schmal: Mehr als
 * diese Handvoll Operationen braucht {@link FileStore} nicht, und so laesst er
 * sich in Tests durch eine Attrappe ersetzen.
 */
public interface ObjectStore {

    record Info(long size, Instant lastModified) {}

    Optional<Info> head(String key) throws IOException;

    void upload(Path file, String key) throws IOException;

    /** Laedt das Objekt in die Zieldatei (die vorher nicht existieren muss). */
    void download(String key, Path target) throws IOException;

    /**
     * Oeffnet das Objekt zum Lesen.
     *
     * @param start erstes Byte; null = ganzes Objekt
     * @param endInclusive letztes Byte (inklusive), nur zusammen mit start
     */
    InputStream open(String key, Long start, Long endInclusive) throws IOException;

    void delete(String key) throws IOException;

    /** Loescht alle Objekte, deren Schluessel mit dem Praefix beginnt. */
    int deletePrefix(String prefix) throws IOException;

    List<String> list(String prefix) throws IOException;

    /** Kurzer Erreichbarkeitstest beim Start; wirft mit verstaendlicher Meldung. */
    void check() throws IOException;

    /** Anzeigename fuer Protokolle, z.B. "s3://bucket/prefix". */
    String describe();
}
