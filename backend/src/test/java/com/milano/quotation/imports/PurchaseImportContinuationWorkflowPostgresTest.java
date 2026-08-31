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
    @Autowired PurchaseImportTaskService tasks;
    @Autowired com.milano.quotation.purchase.PurchaseProductService products;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired org.springframework.web.context.WebApplicationContext webContext;
    @MockitoBean AsyncPurchaseImportProcessor processor;
    @MockitoBean AssetStorageService storage;

    @org.junit.jupiter.api.AfterEach
    void finishSyntheticRunningTasks() {
        // This schema belongs only to this test class. The processor is mocked,
        // so running-state fixtures must release the single-active-task slot.
        jdbc.update("UPDATE import_job SET status='cancelled' WHERE status IN ('queued','parsing','import-queued','importing','rollback-queued','rolling-back')");
    }

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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ready","failed","cancelled"})
    void deletesOnlyUnappliedStagingAndRetainsBusinessDataAndAudit(String status) {
        var source=source();var baseline=prepare(source,values(sku(source,"KEEP"),"保留商品"));confirmAndApply(baseline);
        var asset=attachManualImageAndNotes(row(baseline,2).appliedProductId);
        var disposable=prepare(source(),values(sku(source,"STAGED"),"仅暂存"));
        jdbc.update("UPDATE import_job SET status=? WHERE id=?",status,disposable.id);
        var part=UUID.randomUUID();
        jdbc.update("INSERT INTO import_part(id,job_id,part_number,object_key,sha256,size_bytes,original_name,status,created_at) VALUES (?,?,1,'fixture/part',?,1,'part.zip','uploaded',now())",part,disposable.id,"a".repeat(64));
        jdbc.update("INSERT INTO migration_manifest_entry(id,job_id,import_part_id,sku,image_type,file_name,status,asset_id,updated_at) VALUES (?,?,?,'STAGED','product','shared.png','validated',?,now())",UUID.randomUUID(),disposable.id,part,asset);
        var before=businessFingerprint();
        assertEquals("delete",tasks.inspect(disposable.id).action());tasks.delete(disposable.id);
        assertFalse(jobs.existsById(disposable.id));
        for(var table:List.of("purchase_import_row","import_part","migration_manifest_entry"))
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE job_id=?",Integer.class,disposable.id));
        assertEquals(before,businessFingerprint());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id=? AND action='purchase.import-task-delete'",Integer.class,disposable.id.toString()));
    }

    @Test void archiveRetainsNoSkuContinuationAndBackfillAndCanBeRestored() {
        var source=source();var first=prepare(source,values("","无编号原商品"));confirmAndApply(first);
        var product=row(first,2).appliedProductId;var asset=attachManualImageAndNotes(product);
        var before=businessFingerprint();assertEquals("archive",tasks.inspect(first.id).action());
        assertThrows(AppException.class,()->tasks.delete(first.id));tasks.archive(first.id);
        assertEquals(before,businessFingerprint());assertNotNull(reload(first).archivedAt);
        assertTrue(async.list(org.springframework.data.domain.PageRequest.of(0,100),true).stream().anyMatch(j->j.id().equals(first.id)));
        assertFalse(async.list(org.springframework.data.domain.PageRequest.of(0,100)).stream().anyMatch(j->j.id().equals(first.id)));
        assertThrows(AppException.class,()->async.requestRollback(first.id));
        var repeat=prepare(source,values("","无编号原商品"));
        assertEquals(1,reload(repeat).payload.path("continuation").path("skippedRows").asInt());
        var formal=sku(source,"FORMAL");var backfill=prepare(source,values(formal,"无编号原商品"));
        assertEquals(1,reload(backfill).payload.path("continuation").path("skuBackfillRows").asInt());
        var count=productCount();confirmAndApply(backfill);assertEquals(count,productCount());
        assertEquals(product,productId(formal));assertEquals(asset,imageId(product));assertEquals("人工维护的备注",payload(product).path("notes").asText());
        tasks.restore(first.id);assertNull(reload(first).archivedAt);
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE resource_id=? AND action IN ('purchase.import-task-archive','purchase.import-task-restore')",Integer.class,first.id.toString()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"failed","rolled-back"})
    void retainsAppliedHistoryEvenWhenFailedOrRolledBack(String status) {
        var source=source();var job=prepare(source,values(sku(source,"HISTORY"),"历史商品"));confirmAndApply(job);
        if(status.equals("rolled-back"))batches.rollback(job.id);
        else jdbc.update("UPDATE import_job SET status='failed',processed_rows=0 WHERE id=?",job.id);
        var before=businessFingerprint();assertEquals("archive",tasks.inspect(job.id).action());
        assertThrows(AppException.class,()->tasks.delete(job.id));tasks.archive(job.id);
        assertEquals(1,rows.countByJobIdAndAppliedAtIsNotNull(job.id));assertEquals(before,businessFingerprint());
        assertThrows(AppException.class,()->async.retry(job.id));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"queued","parsing","import-queued","importing","rollback-queued","rolling-back"})
    void refusesRunningTasksWithoutChangingTheirState(String status) {
        var source=source();var job=prepare(source,values(sku(source,"RUN"),"运行中"));
        jdbc.update("UPDATE import_job SET status=? WHERE id=?",status,job.id);
        assertEquals("blocked",tasks.inspect(job.id).action());
        assertThrows(AppException.class,()->tasks.delete(job.id));assertThrows(AppException.class,()->tasks.archive(job.id));
        assertEquals(status,reload(job).status);assertEquals(1,rows.countByJobId(job.id));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    @org.junit.jupiter.api.Timeout(90)
    void confirmationAndDeletionSerializeOnTheSameTaskLock(boolean deleteFirst) throws Exception {
        var source=source();var job=prepare(source,values(sku(source,"RACE"),"并发任务"));
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var ready=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var started=new java.util.concurrent.CountDownLatch(1);
        var firstPid=new java.util.concurrent.atomic.AtomicInteger();var secondPid=new java.util.concurrent.atomic.AtomicInteger();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(()->tx.executeWithoutResult(ignored->{
                firstPid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                if(deleteFirst)tasks.delete(job.id);else async.confirm(job.id,Map.of());
                ready.countDown();
                try{assertTrue(release.await(40,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            }));
            if(!ready.await(20,java.util.concurrent.TimeUnit.SECONDS)){first.get(1,java.util.concurrent.TimeUnit.SECONDS);fail("First operation did not reach its locked state");}
            var second=executor.submit(()->tx.executeWithoutResult(ignored->{
                secondPid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));started.countDown();
                if(deleteFirst)async.confirm(job.id,Map.of());else tasks.delete(job.id);
            }));
            assertTrue(started.await(20,java.util.concurrent.TimeUnit.SECONDS));
            var deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);boolean waiting=false;
            while(System.nanoTime()<deadline&&!waiting){waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))",Boolean.class,firstPid.get(),secondPid.get()));if(!waiting)Thread.sleep(25);}
            assertTrue(waiting,"Must observe a real database row-lock wait");release.countDown();first.get(20,java.util.concurrent.TimeUnit.SECONDS);
            var error=assertThrows(java.util.concurrent.ExecutionException.class,()->second.get(20,java.util.concurrent.TimeUnit.SECONDS));assertInstanceOf(AppException.class,error.getCause());
            if(deleteFirst)assertFalse(jobs.existsById(job.id));else{assertEquals("import-queued",reload(job).status);assertEquals(1,rows.countByJobId(job.id));}
            assertEquals(0,countSku(sku(source,"RACE")));
        }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
    }

    @Test void maintenanceEndpointsRequirePurchasePermissionAndRemovedMigrationHasNoHandler() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var id=UUID.randomUUID();
        for(var suffix:List.of("/archive","/restore"))mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/purchase-imports/jobs/"+id+suffix)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("reader").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_quotation")))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/purchase-imports/jobs/"+id)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("reader"))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        var mappings=webContext.getBean("requestMappingHandlerMapping",org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        assertTrue(mappings.getHandlerMethods().values().stream().noneMatch(method->method.getBeanType().getSimpleName().startsWith("ImageMigration")));
        assertTrue(mappings.getHandlerMethods().values().stream().anyMatch(method->method.getBeanType().getSimpleName().equals("BusinessMigrationController")));
    }

    private String businessFingerprint(){
        var result=new StringBuilder();
        for(var table:List.of("purchase_product","purchase_product_image","asset_object","quotation_record","quotation_draft","quotation_template","supplier_record"))
            result.append(jdbc.queryForObject("SELECT md5(coalesce(string_agg(to_jsonb(t)::text,'|' ORDER BY to_jsonb(t)::text),'')) FROM "+table+" t",String.class));
        return result.toString();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"single", "bundle", "structured-quote", "draft-product", "draft-search", "draft-bundle", "template", "version"})
    void rollbackPreservesEntireBatchWhenAnyProductIsReferencedOrModified(String kind) {
        var source=source();var sku=sku(source,"B");
        var job=prepare(source,values(sku(source,"A"),"可清理商品"),values(sku,"被保护商品"));
        confirmAndApply(job);
        var first=row(job,2).appliedProductId;var second=row(job,3).appliedProductId;
        if(kind.equals("version"))jdbc.update("UPDATE purchase_product SET version=version+1 WHERE id=?",second);
        else seedReference(kind,sku);
        var beforeFirst=payload(first);var beforeSecond=payload(second);
        var beforeCount=productCount();
        var error=assertThrows(AppException.class,()->batches.rollback(job.id));
        assertTrue(error.getMessage().contains("已阻止整批回滚"));
        assertEquals(beforeCount,productCount());assertEquals(beforeFirst,payload(first));assertEquals(beforeSecond,payload(second));
        assertNull(row(job,2).rolledBackAt);assertNull(row(job,3).rolledBackAt);
        assertEquals("completed",reload(job).status);
    }

    @Test void rollbackUnreferencedBatchRetainsImportHistory() {
        var source=source();var first=sku(source,"A");var second=sku(source,"B");
        var job=prepare(source,values(first,"商品A"),values(second,"商品B"));confirmAndApply(job);
        batches.rollback(job.id);
        assertEquals(0,countSku(first));assertEquals(0,countSku(second));
        assertNotNull(row(job,2).rolledBackAt);assertNotNull(row(job,3).rolledBackAt);
        assertEquals("rolled-back",reload(job).status);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"single", "bundle", "draft-product", "template"})
    @org.junit.jupiter.api.Timeout(90)
    void rollbackWaitsForConcurrentReferenceAndThenPreservesWholeBatch(String kind) throws Exception {
        var source=source();var sku=sku(source,"B");
        var job=prepare(source,values(sku(source,"A"),"商品A"),values(sku,"商品B"));confirmAndApply(job);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var writerReady=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var cleanerReady=new java.util.concurrent.CountDownLatch(1);
        var writerPid=new java.util.concurrent.atomic.AtomicInteger();var cleanerPid=new java.util.concurrent.atomic.AtomicInteger();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer=executor.submit(()->tx.executeWithoutResult(ignored->{
                writerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                if(kind.equals("single")||kind.equals("bundle"))assertTrue(products.notQuoteReadyLocked(List.of(sku)).isEmpty());
                else products.lockStructuredReferences(mapper.createObjectNode().put("skuSearch",sku));
                seedReference(kind,sku);writerReady.countDown();
                try{assertTrue(release.await(40,java.util.concurrent.TimeUnit.SECONDS));}
                catch(InterruptedException ex){Thread.currentThread().interrupt();throw new RuntimeException(ex);}
            }));
            assertTrue(writerReady.await(20,java.util.concurrent.TimeUnit.SECONDS));
            var cleaner=executor.submit(()->tx.executeWithoutResult(ignored->{
                cleanerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));cleanerReady.countDown();
                batches.rollback(job.id);
            }));
            assertTrue(cleanerReady.await(20,java.util.concurrent.TimeUnit.SECONDS));
            awaitBlockedBy(cleanerPid.get(),writerPid.get());release.countDown();
            writer.get(20,java.util.concurrent.TimeUnit.SECONDS);
            var error=assertThrows(java.util.concurrent.ExecutionException.class,()->cleaner.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(AppException.class,error.getCause());
            assertEquals(1,countSku(sku));assertEquals(1,countSku(sku(source,"A")));
            assertNull(row(job,2).rolledBackAt);assertNull(row(job,3).rolledBackAt);
        }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
    }

    @Test @org.junit.jupiter.api.Timeout(90)
    void concurrentDraftCannotSaveAProductDeletedWhileWaitingForItsLock() throws Exception {
        var source=source();var sku=sku(source,"A");
        var job=prepare(source,values(sku,"商品A"));confirmAndApply(job);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var deleting=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var writerReady=new java.util.concurrent.CountDownLatch(1);
        var cleanerPid=new java.util.concurrent.atomic.AtomicInteger();var writerPid=new java.util.concurrent.atomic.AtomicInteger();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var cleaner=executor.submit(()->tx.executeWithoutResult(ignored->{
                cleanerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));batches.rollback(job.id);deleting.countDown();
                try{assertTrue(release.await(40,java.util.concurrent.TimeUnit.SECONDS));}
                catch(InterruptedException ex){Thread.currentThread().interrupt();throw new RuntimeException(ex);}
            }));
            assertTrue(deleting.await(20,java.util.concurrent.TimeUnit.SECONDS));
            var writer=executor.submit(()->tx.executeWithoutResult(ignored->{
                writerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));writerReady.countDown();
                products.lockStructuredReferences(mapper.createObjectNode().put("skuSearch",sku));seedReference("draft-search",sku);
            }));
            assertTrue(writerReady.await(20,java.util.concurrent.TimeUnit.SECONDS));
            awaitBlockedBy(writerPid.get(),cleanerPid.get());release.countDown();cleaner.get(20,java.util.concurrent.TimeUnit.SECONDS);
            var error=assertThrows(java.util.concurrent.ExecutionException.class,()->writer.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(AppException.class,error.getCause());assertEquals(0,countSku(sku));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM quotation_draft WHERE payload->>'skuSearch'=?",Integer.class,sku));
        }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
    }

    private void awaitBlockedBy(int waitingPid,int blockingPid) throws InterruptedException {
        var deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))",Boolean.class,blockingPid,waitingPid)))return;
            Thread.sleep(25);
        }
        fail("Expected a real PostgreSQL row-lock wait");
    }

    private void seedReference(String kind,String sku) {
        var payload=mapper.createObjectNode();
        switch(kind){
            case "single" -> payload.put("primarySku",sku);
            case "bundle" -> payload.put("primarySku","UNRELATED、"+sku);
            case "draft-product" -> payload.putObject("product").put("sku",sku);
            case "draft-search" -> payload.put("skuSearch",sku);
            default -> payload.putArray("bundleItems").addObject().put("sku",sku);
        }
        if(kind.startsWith("draft"))jdbc.update("INSERT INTO quotation_draft(owner_account,payload,version,updated_at) VALUES (?,?::jsonb,0,now())","D-"+UUID.randomUUID(),payload.toString());
        else if(kind.equals("template"))jdbc.update("INSERT INTO quotation_template(id,owner_account,name,payload,version,created_at,updated_at) VALUES (?,'ADMIN',?,?::jsonb,0,now(),now())",UUID.randomUUID(),sku,payload.toString());
        else jdbc.update("INSERT INTO quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at) VALUES (?,?,'ADMIN','pending',?::jsonb,0,now(),now())",UUID.randomUUID(),"Q-"+UUID.randomUUID().toString().substring(0,16),payload.toString());
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
