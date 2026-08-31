package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real Spring/JPA orchestration; JDBC is used only to inspect state and seed manual edits. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.defer-datasource-initialization=false",
        "spring.sql.init.mode=never",
        "spring.session.store-type=none",
        "app.storage.initialize=false"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("databaseAvailable")
class PurchaseImportContinuationWorkflowPostgresTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("quotation_test_workflow").withUsername("quotation_app")
            .withPassword("quotation_workflow_test_password");
    private static String schema = "continuation_workflow_" + UUID.randomUUID().toString().replace("-", "");
    private static String databaseUrl;
    private static String databaseUser;
    private static String databasePassword;
    private static boolean databaseValidated;
    private static boolean reusedSchema;

    @Autowired ImportJobRepository jobs;
    @Autowired PurchaseImportRowRepository rows;
    @Autowired PurchaseImportRowMapper rowMapper;
    @Autowired AsyncPurchaseImportService async;
    @Autowired PurchaseImportBatchService batches;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean AsyncPurchaseImportProcessor processor;
    @MockitoBean AssetStorageService storage;

    static boolean databaseAvailable() {
        return System.getenv("QUOTATION_TEST_POSTGRES_URL") != null || DockerClientFactory.instance().isDockerAvailable();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        databaseUrl = System.getenv("QUOTATION_TEST_POSTGRES_URL");
        var requestedSchema = System.getenv("QUOTATION_TEST_POSTGRES_WORKFLOW_SCHEMA");
        if (databaseUrl == null) {
            if (requestedSchema != null)
                throw new IllegalArgumentException("Schema reuse requires an explicit localhost quotation_test_* database");
            POSTGRES.start();
            databaseUrl = POSTGRES.getJdbcUrl();
            databaseUser = POSTGRES.getUsername();
            databasePassword = POSTGRES.getPassword();
        } else {
            if (!databaseUrl.startsWith("jdbc:postgresql://"))
                throw new IllegalArgumentException("Only a localhost quotation_test_* database may run workflow tests");
            var uri = java.net.URI.create(databaseUrl.substring("jdbc:".length()));
            if (!java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                    || uri.getPath() == null || !uri.getPath().matches("/quotation_test_[a-z0-9_]+"))
                throw new IllegalArgumentException("Only a localhost quotation_test_* database may run workflow tests");
            databaseUser = System.getenv("QUOTATION_TEST_POSTGRES_USER");
            databasePassword = System.getenv("QUOTATION_TEST_POSTGRES_PASSWORD");
            if (requestedSchema != null) {
                if (!requestedSchema.matches("continuation_(test|workflow)_[a-f0-9]{32}"))
                    throw new IllegalArgumentException("Only a continuation test schema may be reused");
                var probe = new JdbcTemplate(new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword));
                if (!Boolean.TRUE.equals(probe.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname=?)", Boolean.class, requestedSchema)))
                    throw new IllegalArgumentException("The explicitly reused continuation test schema must already exist");
                schema = requestedSchema;
                reusedSchema = true;
            }
        }
        databaseValidated = true;
        registry.add("spring.datasource.url", () -> databaseUrl + (databaseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public");
        registry.add("spring.datasource.username", () -> databaseUser);
        registry.add("spring.datasource.password", () -> databasePassword);
        registry.add("spring.flyway.url", () -> databaseUrl);
        registry.add("spring.flyway.user", () -> databaseUser);
        registry.add("spring.flyway.password", () -> databasePassword);
        registry.add("spring.flyway.schemas", () -> schema);
        registry.add("spring.flyway.default-schema", () -> schema);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
    }

    @AfterAll
    static void cleanOnlyTheIsolatedTestSchema() {
        try {
            if (databaseValidated && !reusedSchema && schema.matches("continuation_workflow_[a-f0-9]{32}")) {
                var cleanup = new JdbcTemplate(new DriverManagerDataSource(databaseUrl, databaseUser, databasePassword));
                cleanup.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        } finally {
            if (POSTGRES.isRunning()) POSTGRES.stop();
        }
    }

    @Test
    void stageReadyConfirmApplyAndRollbackKeepOldRowsUntouchedAndPersistTheRefreshedSummary() {
        var source = source();
        var originalSku = sku(source, "ORIGINAL");
        var addedSku = sku(source, "ADDED");
        var first = prepare(source, values(originalSku, "原商品"));
        // Both previews are ready before any source row succeeds. Confirmation
        // must commit a refreshed summary after the first job claims its row.
        var next = prepare(source, values(originalSku, "原商品"), values(addedSku, "追加商品"));
        assertEquals(0, reload(next).payload.path("continuation").path("skippedRows").asInt());
        assertEquals(2, reload(next).payload.path("continuation").path("pendingRows").asInt());
        assertEquals(2, reload(next).addedRows);
        confirmAndApply(first);
        var originalId = productId(originalSku);
        var assetId = attachManualImageAndNotes(originalId);
        var beforePayload = payload(originalId);
        var beforeVersion = version(originalId);

        async.confirm(next.id, Map.of());
        var confirmed = reload(next);
        assertEquals("import-queued", confirmed.status);
        assertNotNull(confirmed.confirmedAt);
        assertEquals(1, confirmed.payload.path("continuation").path("skippedRows").asInt());
        assertEquals(1, confirmed.payload.path("continuation").path("pendingRows").asInt());
        assertEquals(1, confirmed.addedRows);
        assertEquals(0, confirmed.updatedRows);
        assertEquals("history-skipped", row(next, 2).validationStatus);
        assertTrue(confirmed.payload.path("_continuationRevision").canConvertToLong());
        async.markImporting(next.id);
        batches.apply(next.id);

        assertEquals("completed", reload(next).status);
        assertEquals(1, reload(next).processedRows);
        assertNull(row(next, 2).appliedAt);
        assertNotNull(row(next, 3).appliedAt);
        assertEquals(beforePayload, payload(originalId));
        assertEquals(beforeVersion, version(originalId));
        assertEquals(assetId, imageId(originalId));
        assertNotNull(productId(addedSku));

        async.requestRollback(next.id);
        async.markRollingBack(next.id);
        batches.rollback(next.id);
        assertEquals("rolled-back", reload(next).status);
        assertEquals(0, countSku(addedSku));
        assertEquals(originalId, productId(originalSku));
        assertEquals(beforePayload, payload(originalId));
        assertEquals(beforeVersion, version(originalId));
        assertEquals(assetId, imageId(originalId));
    }

    @Test
    void backfillsOnlySkuOnTheSameProductThenSkipsRepeatedAndBlankUploadsAndRollsBackWithoutDeletingIt() {
        var source = source();
        var first = prepare(source, values("", "无SKU原商品"));
        confirmAndApply(first);
        var originalRow = row(first, 2);
        var originalSku = originalRow.sku;
        var originalId = originalRow.appliedProductId;
        assertNotNull(originalId);
        assertTrue(originalSku.startsWith("AUTO-"));
        var assetId = attachManualImageAndNotes(originalId);
        var beforePayload = payload(originalId);
        var beforeVersion = version(originalId);
        var beforeCount = productCount();
        var formalSku = sku(source, "FORMAL");

        var backfill = prepare(source, values(formalSku, "无SKU原商品"));
        assertEquals("sku-backfill", row(backfill, 2).importAction);
        assertEquals("valid", row(backfill, 2).validationStatus);
        assertEquals(1, reload(backfill).payload.path("continuation").path("skuBackfillRows").asInt());
        assertEquals(0, reload(backfill).addedRows);
        assertEquals(0, reload(backfill).updatedRows);
        confirmAndApply(backfill);

        assertEquals(originalId, productId(formalSku));
        assertEquals(originalId, row(backfill, 2).appliedProductId);
        assertEquals(beforeCount, productCount());
        assertEquals(beforeVersion + 1, version(originalId));
        assertEquals("人工维护的备注", payload(originalId).path("notes").asText());
        assertEquals(9.25, payload(originalId).path("purchasePriceCny").asDouble());
        assertEquals(beforePayload.path("productImage"), payload(originalId).path("productImage"));
        assertEquals(assetId, imageId(originalId));
        assertEquals(0, countSku(originalSku));

        var afterVersion = version(originalId);
        var afterPayload = payload(originalId);
        for (var inputSku : List.of(formalSku, "")) {
            var repeated = prepare(source, values(inputSku, "无SKU原商品"));
            assertEquals("history-skipped", row(repeated, 2).validationStatus);
            assertEquals(0, reload(repeated).payload.path("continuation").path("pendingRows").asInt());
            assertThrows(AppException.class, () -> async.confirm(repeated.id, Map.of()));
            assertEquals(1, reload(repeated).payload.path("continuation").path("skippedRows").asInt());
            assertEquals(originalId, productId(formalSku));
            assertEquals(afterVersion, version(originalId));
            assertEquals(afterPayload, payload(originalId));
            assertNull(row(repeated, 2).appliedAt);
        }

        async.requestRollback(backfill.id);
        async.markRollingBack(backfill.id);
        batches.rollback(backfill.id);
        assertEquals("rolled-back", reload(backfill).status);
        assertEquals(originalId, productId(originalSku));
        assertEquals(0, countSku(formalSku));
        assertEquals(beforeCount, productCount());
        assertEquals(beforePayload, payload(originalId));
        assertEquals(assetId, imageId(originalId));
        assertNotNull(row(backfill, 2).rolledBackAt);
    }

    @Test
    void overlengthSkuRemainsAnErrorRowWithoutAbortingStagingOrChangingOriginalProduct() {
        var source = source();
        var first = prepare(source, values("", "超长SKU校验原商品"));
        confirmAndApply(first);
        var originalRow = row(first, 2);
        var originalId = originalRow.appliedProductId;
        var originalSku = originalRow.sku;
        var beforePayload = payload(originalId);
        var beforeVersion = version(originalId);
        var beforeCount = productCount();
        var tooLongSku = "X".repeat(97);

        var invalid = assertDoesNotThrow(() -> prepare(source, values(tooLongSku, "超长SKU校验原商品")));
        var preview = reload(invalid);
        var errorRow = row(invalid, 2);
        assertEquals("ready", preview.status);
        assertEquals(1, preview.errorRows);
        assertEquals("error", errorRow.validationStatus);
        assertEquals("skip", errorRow.importAction);
        assertEquals(tooLongSku, errorRow.payload.path("sku").asText());
        assertTrue(preview.payload.path("continuation").path("blocked").asBoolean());
        assertThrows(AppException.class, () -> async.confirm(invalid.id, Map.of()));
        assertEquals("ready", reload(invalid).status);
        assertNull(row(invalid, 2).appliedAt);
        assertEquals(originalId, productId(originalSku));
        assertEquals(beforePayload, payload(originalId));
        assertEquals(beforeVersion, version(originalId));
        assertEquals(beforeCount, productCount());
    }

    private ImportJob prepare(String source, String[]... sourceRows) {
        var job = new ImportJob();
        job.id = UUID.randomUUID(); job.jobType = AsyncPurchaseImportService.JOB_TYPE;
        job.status = "parsing"; job.phase = "parsing"; job.sourceName = source;
        job.sourceHash = "a".repeat(64); job.requestedBy = "ADMIN";
        job.createdAt = Instant.now(); job.updatedAt = job.createdAt; job.heartbeatAt = job.createdAt;
        job.payload = mapper.createObjectNode();
        PurchaseImportContinuationService.initialize(job);
        jobs.saveAndFlush(job);
        var mapped = new java.util.ArrayList<PurchaseImportRowMapper.MappedRow>();
        for (int index = 0; index < sourceRows.length; index++)
            mapped.add(rowMapper.map(job.id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT),
                    1, "采购", index + 2, sourceRows[index], new PurchaseWorkbookSchema(PurchaseWorkbookSchema.Version.LEGACY)));
        batches.stage(job.id, mapped);
        batches.ready(job.id);
        return job;
    }

    private void confirmAndApply(ImportJob job) {
        async.confirm(job.id, Map.of());
        async.markImporting(job.id);
        batches.apply(job.id);
        assertEquals("completed", reload(job).status);
    }

    private UUID attachManualImageAndNotes(UUID productId) {
        var assetId = UUID.randomUUID();
        var hex = assetId.toString().replace("-", "");
        jdbc.update("""
                INSERT INTO asset_object(id,sha256,object_key,media_type,size_bytes,original_name,storage_state,created_at)
                VALUES (?,?,?,'image/png',8,'workflow.png','published',now())
                """, assetId, hex + hex, "workflow-fixture/" + assetId);
        jdbc.update("""
                INSERT INTO purchase_product_image(id,product_id,asset_id,image_type,sort_order)
                VALUES (?,?,?,'product',0)
                """, UUID.randomUUID(), productId, assetId);
        jdbc.update("""
                UPDATE purchase_product SET payload=payload || jsonb_build_object(
                    'notes','人工维护的备注','purchasePriceCny',9.25,
                    'productImage',?::text,'image',?::text),version=version+1,updated_at=now()
                WHERE id=?
                """, "/api/v1/assets/" + assetId, "/api/v1/assets/" + assetId, productId);
        return assetId;
    }

    private ImportJob reload(ImportJob job) { return jobs.findById(job.id).orElseThrow(); }
    private PurchaseImportRow row(ImportJob job, int line) {
        return rows.findFirstByJobIdAndSourceSheetAndSourceRow(job.id, "采购", line).orElseThrow();
    }
    private UUID productId(String sku) {
        return jdbc.queryForObject("SELECT id FROM purchase_product WHERE sku=?", UUID.class, sku);
    }
    private UUID imageId(UUID productId) {
        return jdbc.queryForObject("SELECT asset_id FROM purchase_product_image WHERE product_id=? AND image_type='product'", UUID.class, productId);
    }
    private JsonNode payload(UUID productId) {
        return mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM purchase_product WHERE id=?", String.class, productId));
    }
    private long version(UUID productId) {
        return jdbc.queryForObject("SELECT version FROM purchase_product WHERE id=?", Long.class, productId);
    }
    private int countSku(String sku) { return jdbc.queryForObject("SELECT count(*) FROM purchase_product WHERE sku=?", Integer.class, sku); }
    private long productCount() { return jdbc.queryForObject("SELECT count(*) FROM purchase_product", Long.class); }
    private static String source() { return UUID.randomUUID().toString().substring(0, 8) + "-workflow.xlsx"; }
    private static String sku(String source, String suffix) { return "WF-" + source.substring(0, 8).toUpperCase(java.util.Locale.ROOT) + "-" + suffix; }
    private static String[] values(String sku, String category) {
        var values = new String[PurchaseWorkbookSchema.LEGACY_HEADERS.size()];
        Arrays.fill(values, ""); values[0] = sku; values[1] = category; values[4] = "采购员";
        values[5] = "2026-08-31"; values[8] = "40"; values[12] = "1"; values[13] = "7.14";
        return values;
    }
}
