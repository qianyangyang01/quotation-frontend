package com.milano.quotation.imports;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PurchaseAssetMinioPostgresIntegrationTest {
    private static final String ACCESS_KEY = "quotation_test";
    private static final String SECRET_KEY = "quotation_test_secret";
    private static final String BUCKET = "quotation-assets";

    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("quotation_prod").withUsername("quotation_app").withPassword("quotation_test_password");
    @Container static final GenericContainer<?> minio = new GenericContainer<>("minio/minio:RELEASE.2025-07-23T15-54-02Z")
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY).withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data").withExposedPorts(9000);

    @Test void storesObjectAndPersistsItsUuidProductRelation() throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        var client = MinioClient.builder()
                .endpoint("http://" + minio.getHost() + ":" + minio.getMappedPort(9000))
                .credentials(ACCESS_KEY, SECRET_KEY).build();
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
        }

        var bytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var objectKey = "objects/" + sha.substring(0, 2) + "/" + sha;
        client.putObject(PutObjectArgs.builder().bucket(BUCKET).object(objectKey)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1).contentType("image/png").build());

        var assetId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        jdbc.update("insert into asset_object(id,sha256,object_key,media_type,size_bytes,original_name,storage_state,created_at) values (?,?,?,?,?,?,?,now())",
                assetId, sha, objectKey, "image/png", bytes.length, "fixture.png", "published");
        jdbc.update("insert into purchase_product(id,sku,payload,version,created_at,updated_at,catalog_state,quote_ready) values (?,?,?::jsonb,0,now(),now(),'ready',true)",
                productId, "CI-MINIO-UUID-1", "{\"sku\":\"CI-MINIO-UUID-1\"}");
        jdbc.update("insert into purchase_product_image(id,product_id,asset_id,image_type,sort_order) values (?,?,?,'product',0)",
                UUID.randomUUID(), productId, assetId);

        var stored = client.statObject(StatObjectArgs.builder().bucket(BUCKET).object(objectKey).build());
        assertEquals(bytes.length, stored.size());
        assertTrue(client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build()));
        assertEquals(assetId, jdbc.queryForObject("select image.asset_id from purchase_product_image image join asset_object asset on asset.id=image.asset_id where image.product_id=? and asset.object_key=?", UUID.class, productId, objectKey));
    }
}
