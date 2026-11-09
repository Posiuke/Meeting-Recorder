package bbbbot.storage;

import bbbbot.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Legt den S3-Zugang nur an, wenn er gewollt ist (STORAGE_BACKEND=s3). */
@Configuration
public class StorageConfig {

    @Bean(destroyMethod = "")
    @ConditionalOnProperty(prefix = "bbbbot.storage", name = "backend", havingValue = "s3")
    public ObjectStore s3ObjectStore(AppProperties props) {
        return new S3ObjectStore(props.getStorage().getS3());
    }
}
