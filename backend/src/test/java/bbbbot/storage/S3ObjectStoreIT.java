package bbbbot.storage;

import bbbbot.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gegen einen echten S3-kompatiblen Speicher. Laeuft nur mit
 * {@code S3_IT_ENDPOINT} (und {@code S3_IT_BUCKET}), z.B.:
 * <pre>
 * docker run -d -p 19090:9090 -e initialBuckets=recorder-test adobe/s3mock:3.12.0
 * S3_IT_ENDPOINT=http://localhost:19090 S3_IT_BUCKET=recorder-test mvn test -Dtest=S3ObjectStoreIT
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "S3_IT_ENDPOINT", matches = ".+")
class S3ObjectStoreIT {

    @TempDir
    Path tmp;

    private S3ObjectStore store;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties.S3 config = new AppProperties.S3();
        config.setEndpoint(System.getenv("S3_IT_ENDPOINT"));
        config.setBucket(System.getenv().getOrDefault("S3_IT_BUCKET", "recorder-test"));
        config.setRegion("eu-central-1");
        config.setPathStyle(true);
        config.setAccessKey(System.getenv().getOrDefault("S3_IT_ACCESS_KEY", "test"));
        config.setSecretKey(System.getenv().getOrDefault("S3_IT_SECRET_KEY", "test"));
        store = new S3ObjectStore(config);
        store.check();
        base = "it-" + UUID.randomUUID() + "/";
    }

    @AfterEach
    void tearDown() throws Exception {
        store.deletePrefix(base);
        store.close();
    }

    @Test
    void kleineDateiHochHolenBereichLesenLoeschen() throws Exception {
        Path file = tmp.resolve("a.txt");
        Files.writeString(file, "0123456789");
        store.upload(file, base + "rec/a.txt");

        assertThat(store.head(base + "rec/a.txt")).hasValueSatisfying(i -> assertThat(i.size()).isEqualTo(10));
        assertThat(store.head(base + "rec/fehlt.txt")).isEmpty();
        try (InputStream in = store.open(base + "rec/a.txt", 3L, 6L)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("3456");
        }
        Path back = tmp.resolve("back.txt");
        store.download(base + "rec/a.txt", back);
        assertThat(Files.readString(back)).isEqualTo("0123456789");

        assertThat(store.list(base + "rec/")).containsExactly(base + "rec/a.txt");
        store.delete(base + "rec/a.txt");
        assertThat(store.head(base + "rec/a.txt")).isEmpty();
    }

    @Test
    void grosseDateiGehtPerMultipartUndBleibtBytegleich() throws Exception {
        // 2,5 Teile: prueft auch den kurzen letzten Teil
        byte[] data = new byte[S3ObjectStore.PART_SIZE * 2 + S3ObjectStore.PART_SIZE / 2];
        new Random(42).nextBytes(data);
        Path file = tmp.resolve("big.bin");
        Files.write(file, data);

        store.upload(file, base + "big.bin");

        assertThat(store.head(base + "big.bin")).hasValueSatisfying(i -> assertThat(i.size()).isEqualTo(data.length));
        Path back = tmp.resolve("big-back.bin");
        store.download(base + "big.bin", back);
        assertThat(Files.readAllBytes(back)).isEqualTo(data);
        long from = S3ObjectStore.PART_SIZE - 5L;
        try (InputStream in = store.open(base + "big.bin", from, from + 9)) {
            byte[] slice = in.readAllBytes();
            assertThat(slice).hasSize(10);
            assertThat(slice[0]).isEqualTo(data[(int) from]);
            assertThat(slice[9]).isEqualTo(data[(int) from + 9]);
        }
    }

    @Test
    void deletePrefixEntferntAllesDarunter() throws Exception {
        Path file = tmp.resolve("x");
        Files.writeString(file, "x");
        store.upload(file, base + "r1/a");
        store.upload(file, base + "r1/docs/b");
        store.upload(file, base + "r2/c");

        assertThat(store.deletePrefix(base + "r1/")).isEqualTo(2);
        assertThat(store.list(base)).containsExactly(base + "r2/c");
    }
}
