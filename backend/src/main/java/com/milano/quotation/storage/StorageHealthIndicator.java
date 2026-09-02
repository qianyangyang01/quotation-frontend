package com.milano.quotation.storage;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class StorageHealthIndicator implements HealthIndicator {
    private final MinioClient minio;
    private final String bucket;
    public StorageHealthIndicator(MinioClient minio, @Value("${app.storage.bucket}") String bucket) { this.minio = minio; this.bucket = bucket; }
    @Override public Health health() {
        try { return minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()) ? Health.up().withDetail("bucket", bucket).build() : Health.down().withDetail("bucket", bucket).build(); }
        catch (Exception error) { return Health.down(error).withDetail("bucket", bucket).build(); }
    }
}
