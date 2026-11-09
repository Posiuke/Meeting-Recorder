package bbbbot.storage;

import bbbbot.config.AppProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * S3-Anbindung ueber den synchronen Client des AWS-SDK v2. Grosse Dateien gehen
 * per Multipart hoch (einzelnes PUT ist auf 5 GB begrenzt), in Teilen fester
 * Groesse aus einem Puffer - so kann das SDK einen fehlgeschlagenen Teil
 * wiederholen, ohne die Datei erneut lesen zu muessen.
 */
public class S3ObjectStore implements ObjectStore, AutoCloseable {

    /** Ab dieser Groesse Multipart; zugleich die Groesse eines Teils. */
    static final int PART_SIZE = 16 * 1024 * 1024;

    private final S3Client client;
    private final String bucket;
    private final String description;

    public S3ObjectStore(AppProperties.S3 config) {
        if (config.getBucket() == null || config.getBucket().isBlank()) {
            throw new IllegalStateException("STORAGE_BACKEND=s3 braucht S3_BUCKET");
        }
        this.bucket = config.getBucket().trim();
        this.client = buildClient(config);
        String prefix = config.getPrefix() == null ? "" : config.getPrefix().trim();
        this.description = "s3://" + bucket + (prefix.isEmpty() ? "" : "/" + prefix)
                + (config.getEndpoint() == null || config.getEndpoint().isBlank()
                        ? "" : " (" + config.getEndpoint().trim() + ")");
    }

    private static S3Client buildClient(AppProperties.S3 config) {
        S3ClientBuilder builder = S3Client.builder()
                .region(region(config))
                .credentialsProvider(credentials(config))
                .forcePathStyle(config.isPathStyle())
                // Pruefsummen nur, wo S3 sie verlangt: Neuere SDK-Standards
                // vertragen sich nicht mit jedem S3-kompatiblen Speicher.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        if (config.getEndpoint() != null && !config.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(config.getEndpoint().trim()));
        }
        return builder.build();
    }

    private static Region region(AppProperties.S3 config) {
        if (config.getRegion() != null && !config.getRegion().isBlank()) {
            return Region.of(config.getRegion().trim());
        }
        try {
            // Auf ECS/EC2 setzt AWS die Region selbst (AWS_REGION bzw. Metadaten).
            return new DefaultAwsRegionProviderChain().getRegion();
        } catch (SdkException e) {
            // S3-kompatible Speicher ignorieren die Region meist, brauchen aber eine.
            return Region.US_EAST_1;
        }
    }

    private static AwsCredentialsProvider credentials(AppProperties.S3 config) {
        if (config.getAccessKey() != null && !config.getAccessKey().isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                    config.getAccessKey().trim(), config.getSecretKey() == null ? "" : config.getSecretKey().trim()));
        }
        return DefaultCredentialsProvider.builder().build();
    }

    @Override
    public Optional<Info> head(String key) throws IOException {
        try {
            HeadObjectResponse response = client.headObject(r -> r.bucket(bucket).key(key));
            return Optional.of(new Info(response.contentLength(), response.lastModified()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return Optional.empty();
            throw io("HEAD " + key, e);
        } catch (SdkException e) {
            throw io("HEAD " + key, e);
        }
    }

    @Override
    public void upload(Path file, String key) throws IOException {
        long size = Files.size(file);
        try {
            if (size <= PART_SIZE) {
                client.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromFile(file));
            } else {
                multipartUpload(file, key);
            }
        } catch (SdkException e) {
            throw io("Upload " + key, e);
        }
    }

    private void multipartUpload(Path file, String key) throws IOException {
        String uploadId = client.createMultipartUpload(r -> r.bucket(bucket).key(key)).uploadId();
        List<CompletedPart> parts = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[PART_SIZE];
            int partNumber = 1;
            while (true) {
                int filled = in.readNBytes(buffer, 0, PART_SIZE);
                if (filled <= 0) break;
                int number = partNumber++;
                String etag = client.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId)
                                .partNumber(number).contentLength((long) filled),
                        RequestBody.fromBytes(filled == PART_SIZE ? buffer : java.util.Arrays.copyOf(buffer, filled)))
                        .eTag();
                parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
                if (filled < PART_SIZE) break;
            }
            client.completeMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build()));
        } catch (IOException | RuntimeException e) {
            try {
                client.abortMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId));
            } catch (RuntimeException ignored) {
                // Halbe Uploads raeumt notfalls die Lifecycle-Regel des Buckets ab.
            }
            throw e;
        }
    }

    @Override
    public void download(String key, Path target) throws IOException {
        try {
            client.getObject(r -> r.bucket(bucket).key(key), ResponseTransformer.toFile(target));
        } catch (SdkException e) {
            throw io("Download " + key, e);
        }
    }

    @Override
    public InputStream open(String key, Long start, Long endInclusive) throws IOException {
        GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(bucket).key(key);
        if (start != null) {
            request.range("bytes=" + start + "-" + (endInclusive == null ? "" : endInclusive));
        }
        try {
            return client.getObject(request.build());
        } catch (SdkException e) {
            throw io("GET " + key, e);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            client.deleteObject(r -> r.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw io("DELETE " + key, e);
        }
    }

    @Override
    public int deletePrefix(String prefix) throws IOException {
        List<String> keys = list(prefix);
        try {
            for (int from = 0; from < keys.size(); from += 1000) {
                List<ObjectIdentifier> batch = keys.subList(from, Math.min(keys.size(), from + 1000)).stream()
                        .map(k -> ObjectIdentifier.builder().key(k).build())
                        .toList();
                client.deleteObjects(r -> r.bucket(bucket).delete(Delete.builder().objects(batch).quiet(true).build()));
            }
        } catch (SdkException e) {
            throw io("DELETE " + prefix + "*", e);
        }
        return keys.size();
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        try {
            return client.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(prefix)).contents().stream()
                    .map(S3Object::key)
                    .toList();
        } catch (SdkException e) {
            throw io("LIST " + prefix, e);
        }
    }

    @Override
    public void check() throws IOException {
        try {
            client.headBucket(r -> r.bucket(bucket));
        } catch (SdkException e) {
            throw io("Bucket " + bucket + " nicht erreichbar", e);
        }
    }

    @Override
    public String describe() {
        return description;
    }

    private static IOException io(String what, Exception e) {
        return new IOException(what + ": " + e.getMessage(), e);
    }

    @Override
    public void close() {
        client.close();
    }
}
