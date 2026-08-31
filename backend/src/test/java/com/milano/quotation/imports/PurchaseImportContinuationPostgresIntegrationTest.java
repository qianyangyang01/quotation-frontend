package com.milano.quotation.imports;

import com.milano.quotation.common.AppException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIf("databaseAvailable")
class PurchaseImportContinuationPostgresIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("quotation_continuation").withUsername("quotation_app").withPassword("quotation_test_password");
    static JdbcTemplate jdbc;
    static PurchaseImportContinuationService continuation;
    static PurchaseImportJdbcService imports;
    static TransactionTemplate tx;
    static com.zaxxer.hikari.HikariDataSource pool;
    static final JsonMapper json = JsonMapper.builder().build();
    static final PurchaseImportRowMapper rowMapper = new PurchaseImportRowMapper(json);

    static boolean databaseAvailable() {
        return System.getenv("QUOTATION_TEST_POSTGRES_URL") != null || DockerClientFactory.instance().isDockerAvailable();
    }

    @BeforeAll static void setup() {
        String url = System.getenv("QUOTATION_TEST_POSTGRES_URL");
        String user, password;
        if (url == null) {
            postgres.start();
            url = postgres.getJdbcUrl(); user = postgres.getUsername(); password = postgres.getPassword();
        } else {
            var uri = java.net.URI.create(url.substring("jdbc:".length()));
            if (!url.startsWith("jdbc:postgresql://") || !Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                    || uri.getPath() == null || !uri.getPath().matches("/quotation_test_[a-z0-9_]+"))
                throw new IllegalArgumentException("Only a localhost quotation_test_* database may run these tests");
            user = System.getenv("QUOTATION_TEST_POSTGRES_USER"); password = System.getenv("QUOTATION_TEST_POSTGRES_PASSWORD");
        }
        url += (url.contains("?") ? "&" : "?") + "sslmode=disable&connectTimeout=10&socketTimeout=60";
        var schema = System.getenv("QUOTATION_TEST_POSTGRES_SCHEMA");
        if (schema == null) schema = "continuation_test_" + UUID.randomUUID().toString().replace("-", "");
        else if (System.getenv("QUOTATION_TEST_POSTGRES_URL") == null || !schema.matches("continuation_test_[a-f0-9]{32}"))
            throw new IllegalArgumentException("Schema reuse requires a localhost test database and continuation_test_<32 hex> schema");
        var schemaUrl=url+"&currentSchema="+schema+",public";
        Flyway.configure().dataSource(schemaUrl, user, password).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        pool = new com.zaxxer.hikari.HikariDataSource();
        pool.setJdbcUrl(schemaUrl);
        pool.setUsername(user); pool.setPassword(password); pool.setMaximumPoolSize(4); pool.setMinimumIdle(1);
        var ds = pool;
        jdbc = new JdbcTemplate(ds);
        var named = new NamedParameterJdbcTemplate(ds);
        continuation = new PurchaseImportContinuationService(jdbc, named);
        imports = new PurchaseImportJdbcService(named, jdbc);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @AfterAll static void stopContainer() { if(pool!=null)pool.close();if (postgres.isRunning()) postgres.stop(); }
    @BeforeEach void scenario(org.junit.jupiter.api.TestInfo info){System.out.println("Continuation scenario: "+info.getDisplayName());}

    @Test void resumesLegacyWorkbookAtRow32WithoutRegeneratingOldNoSkuProductOrChangingItsImage() {
        var source = source();
        var first = job(source, false);
        var original = new ArrayList<UUID>();
        for (int row = 2; row <= 31; row++) original.add(stage(first, "采购", row,
                values(row == 2 ? "" : sku(source, row), "商品" + row), true));
        jdbc.update("UPDATE purchase_import_row SET payload = payload || '{\"productImage\":\"/api/v1/assets/retained\",\"name\":\"商品 AUTO-OLD\"}'::jsonb WHERE id = ?", original.getFirst());
        // The original asynchronous mapper did not preserve the source weight text.
        jdbc.update("UPDATE purchase_import_row SET payload = payload - 'weightOriginal' WHERE job_id=?", first.id);
        apply(first, original);
        var oldSku = jdbc.queryForObject("SELECT sku FROM purchase_import_row WHERE id = ?", String.class, original.getFirst());
        var before = productCount();
        var next = job(source, true);
        var uploaded = new ArrayList<UUID>();
        for (int row = 2; row <= 32; row++) uploaded.add(stage(next, "采购", row,
                values(row == 2 ? "" : sku(source, row), "商品" + row), false));

        refresh(next);
        assertFalse(summary(next).path("blocked").asBoolean());
        assertEquals(30, summary(next).path("skippedRows").asInt());
        assertEquals(1, summary(next).path("pendingRows").asInt());
        assertEquals(31, summary(next).path("sheets").get(0).path("lastImportedRow").asInt());
        assertEquals(32, summary(next).path("sheets").get(0).path("nextRow").asInt());
        assertEquals("history-skipped", status(uploaded.getFirst()));
        apply(next, uploaded);
        assertEquals(before + 1, productCount());
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM purchase_product WHERE sku = ?", Long.class, oldSku));
        assertEquals("/api/v1/assets/retained", jdbc.queryForObject("SELECT payload ->> 'productImage' FROM purchase_product WHERE sku = ?", String.class, oldSku));

        var unchanged = job(source, true);
        for (int row = 2; row <= 32; row++) stage(unchanged, "采购", row,
                values(row == 2 ? "" : sku(source, row), "商品" + row), false);
        refresh(unchanged);
        assertEquals(31, summary(unchanged).path("skippedRows").asInt());
        assertEquals(0, summary(unchanged).path("pendingRows").asInt());
    }

    @Test void keepsFailedAndUnconfirmedHolesImportableInsteadOfSkippingEverythingBelowMaximumRow() {
        var source = source();
        var previous = job(source, false);
        var good = stage(previous, "采购", 2, values(sku(source, 2), "已成功"), true);
        stage(previous, "采购", 3, values("BAD SKU", "待纠正"), true);
        var noSku = stage(previous, "采购", 4, values("", "无编号成功"), true);
        stage(previous, "采购", 5, values(sku(source, 5), "未执行"), true);
        apply(previous, List.of(good, noSku));
        jdbc.update("UPDATE import_job SET status='failed' WHERE id=?", previous.id);

        var next = job(source, true);
        stage(next, "采购", 2, values(sku(source, 2), "已成功"), false);
        var fixed = stage(next, "采购", 3, values(sku(source, 3), "待纠正"), false);
        stage(next, "采购", 4, values("", "无编号成功"), false);
        var unconfirmed = stage(next, "采购", 5, values(sku(source, 5), "未执行"), false);
        var appended = stage(next, "采购", 6, values(sku(source, 6), "追加"), false);
        refresh(next);
        assertFalse(summary(next).path("blocked").asBoolean());
        assertEquals(2, summary(next).path("skippedRows").asInt());
        assertEquals(3, summary(next).path("pendingRows").asInt());
        assertEquals(3, summary(next).path("sheets").get(0).path("nextRow").asInt());
        assertEquals(1, summary(next).path("sheets").get(0).path("retryRows").asInt());

        var before = productCount();
        apply(next, List.of(fixed)); // Simulate a worker failing after a committed chunk.
        apply(next, List.of(fixed, unconfirmed, appended)); // A stale replay must not write fixed twice.
        assertEquals(before + 3, productCount());
        assertEquals("valid", status(fixed));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM purchase_product WHERE id=?", Long.class, fixed));
    }

    @Test void pendingJobsAreNotHistoryAndLegacyPendingJobsRetainTheirOriginalMode() {
        var source = source();
        var pending = job(source, false);
        stage(pending, "采购", 2, values("", "未确认"), true);
        var next = job(source, true);
        stage(next, "采购", 2, values("", "未确认"), false);
        refresh(next);
        assertFalse(summary(next).path("baselineFound").asBoolean());
        assertEquals(1, summary(next).path("pendingRows").asInt());
        tx.executeWithoutResult(ignored -> continuation.refresh(pending));
        assertFalse(pending.payload.has("continuation"));
    }

    @Test void blocksChangedDeletedRenamedAndFilledOldBlankRowsWithoutWritingProducts() {
        var source = source();
        var previous = job(source, true);
        var first = stage(previous, "采购", 2, values("", "原行"), false);
        var last = stage(previous, "采购", 4, values(sku(source, 4), "后行"), false);
        apply(previous, List.of(first, last));
        long before = productCount();
        var variants = List.of("changed", "deleted", "renamed", "filled-blank");
        for (var variant : variants) {
            var next = job(source, true);
            var sheet = variant.equals("renamed") ? "新工作表" : "采购";
            if (!variant.equals("deleted")) stage(next, sheet, 2,
                    values("", variant.equals("changed") ? "已修改" : "原行"), false);
            if (variant.equals("filled-blank")) stage(next, sheet, 3, values(sku(source, 3), "填入旧空白区"), false);
            stage(next, sheet, 4, values(sku(source, 4), "后行"), false);
            refresh(next);
            assertTrue(summary(next).path("blocked").asBoolean(), variant);
            assertThrows(AppException.class, () -> PurchaseImportContinuationService.requireUnblocked(next), variant);
        }
        assertEquals(before, productCount());
    }

    @Test void rawCellHashDetectsChangedUnrecognizedValuesEvenWhenMappedPayloadRemainsIdentical() {
        var source = source();
        var previous = job(source, true);
        var raw = values(sku(source, 2), "原行");
        raw[13] = "无法识别的原价";
        var first = stage(previous, "采购", 2, raw, false);
        apply(previous, List.of(first));
        var next = job(source, true);
        raw[13] = "另一段错误价格";
        stage(next, "采购", 2, raw, false);
        refresh(next);
        assertTrue(summary(next).path("blocked").asBoolean());
    }

    @Test void ambiguousLegacyNoSkuHistoryIsBlockedRatherThanPickingAnArbitraryOldProduct() {
        var source = source();
        for (int n = 0; n < 2; n++) {
            var previous = job(source, false);
            apply(previous, List.of(stage(previous, "采购", 2, values("", "同一个来源行"), true)));
        }
        var next = job(source, true);
        stage(next, "采购", 2, values("", "同一个来源行"), false);
        refresh(next);
        assertTrue(summary(next).path("blocked").asBoolean());
        assertTrue(summary(next).path("reason").asText().contains("多个不一致"));
    }

    @Test void preservesExplicitDuplicateSkipDecisionAndRejectsNewRowsReusingHistoricalSku() {
        var source = source();
        var previous = job(source, false);
        var selected = stage(previous, "采购", 2, values(sku(source, 2), "选中"), true);
        var ignored = stage(previous, "采购", 3, values(sku(source, 2), "主动忽略"), true);
        jdbc.update("UPDATE purchase_import_row SET validation_status='duplicate-skipped',import_action='skip' WHERE id=?", ignored);
        apply(previous, List.of(selected));

        var next = job(source, true);
        stage(next, "采购", 2, values(sku(source, 2), "选中"), false);
        var stillIgnored = stage(next, "采购", 3, values(sku(source, 2), "主动忽略"), false);
        stage(next, "采购", 4, values(sku(source, 4), "新增"), false);
        refresh(next);
        assertFalse(summary(next).path("blocked").asBoolean());
        assertEquals(2, summary(next).path("skippedRows").asInt());
        assertEquals("history-skipped", status(stillIgnored));

        var changed = job(source, true);
        stage(changed, "采购", 2, values(sku(source, 2), "选中"), false);
        stage(changed, "采购", 3, values(sku(source, 2), "改了忽略行"), false);
        refresh(changed);
        assertTrue(summary(changed).path("blocked").asBoolean());

        var duplicateAppend = job(source, true);
        stage(duplicateAppend, "采购", 2, values(sku(source, 2), "选中"), false);
        stage(duplicateAppend, "采购", 3, values(sku(source, 2), "主动忽略"), false);
        stage(duplicateAppend, "采购", 4, values(sku(source, 2), "末尾重复SKU"), false);
        refresh(duplicateAppend);
        assertTrue(summary(duplicateAppend).path("blocked").asBoolean());
        assertTrue(summary(duplicateAppend).path("reason").asText().contains("不能覆盖"));
    }

    @Test void rollbackRestoresRowsThatAnAlreadyPreparedNextUploadHadSkipped() {
        var source = source();
        var previous = job(source, true);
        var original = stage(previous, "采购", 2, values("", "可回滚"), false);
        apply(previous, List.of(original));
        var next = job(source, true);
        var restored = stage(next, "采购", 2, values("", "可回滚"), false);
        var added = stage(next, "采购", 3, values(sku(source, 3), "新增"), false);
        refresh(next);
        assertEquals("history-skipped", status(restored));
        tx.executeWithoutResult(ignored -> {
            continuation.lockSource(previous);
            assertEquals(0, imports.lockAndCountRollbackConflicts(previous.id));
            imports.rollback(previous.id, List.of(original));
        });
        refresh(next);
        assertFalse(summary(next).path("baselineFound").asBoolean());
        assertEquals(0, summary(next).path("skippedRows").asInt());
        assertEquals("valid", status(restored));
        assertEquals("insert", jdbc.queryForObject("SELECT import_action FROM purchase_import_row WHERE id=?", String.class, restored));
        var before = productCount();
        apply(next, List.of(restored, added));
        assertEquals(before + 2, productCount());
    }

    @Test void detectsRollbackBetweenPreflightAndBatchAndRetryRequeuesEarlierSkippedRows() {
        var source = source();
        var previous = job(source, true);
        var baseline = stage(previous, "采购", 2, values("", "旧行"), false);
        apply(previous, List.of(baseline));
        var next = job(source, true);
        var formerlySkipped = stage(next, "采购", 2, values("", "旧行"), false);
        var last = stage(next, "采购", 3, values(sku(source, 3), "追加"), false);
        refresh(next); // This is the preflight before a queued worker starts its batch.
        tx.executeWithoutResult(ignored -> {
            continuation.lockSource(previous);
            imports.rollback(previous.id, List.of(baseline));
        });
        assertThrows(AppException.class, () -> tx.executeWithoutResult(ignored -> continuation.guardBatch(next, List.of(last))));
        assertNull(jdbc.queryForObject("SELECT applied_at FROM purchase_import_row WHERE id=?", java.sql.Timestamp.class, last));
        refresh(next); // A retry performs the full preflight before fetching its first page.
        assertEquals("valid", status(formerlySkipped));
        assertEquals(2, summary(next).path("pendingRows").asInt());
        apply(next, List.of(formerlySkipped, last));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM purchase_import_row WHERE job_id=? AND applied_at IS NOT NULL", Integer.class, next.id));
    }

    @Test void rejectsLegacyQueuedUploadAfterAppendClaimedSameSource() {
        var source = source();
        var legacy = job(source, false);
        var staleAuto = stage(legacy, "采购", 2, values("", "同一商品"), true);
        var modern = job(source, true);
        var firstAuto = stage(modern, "采购", 2, values("", "同一商品"), false);
        apply(modern, List.of(firstAuto));
        long before = productCount();
        assertThrows(AppException.class, () -> apply(legacy, List.of(staleAuto)));
        assertEquals(before, productCount());
        assertNull(jdbc.queryForObject("SELECT applied_at FROM purchase_import_row WHERE id=?", java.sql.Timestamp.class, staleAuto));
    }

    @Test void preventsAppendOverwritingPreviouslyGeneratedProductAfterItWasPromotedToFormalSku() {
        var source = source();
        var previous = job(source, true);
        var generated = stage(previous, "采购", 2, values("", "历史无SKU商品"), false);
        apply(previous, List.of(generated));
        var formalSku = sku(source, 100);
        jdbc.update("UPDATE purchase_product SET sku=?, payload=payload || jsonb_build_object('sku',?::text,'skuOrigin','manual'), version=version+1 WHERE id=?", formalSku, formalSku, generated);
        var next = job(source, true);
        stage(next, "采购", 2, values("", "历史无SKU商品"), false);
        stage(next, "采购", 3, values(formalSku, "可能覆盖原商品"), false);
        refresh(next);
        assertTrue(summary(next).path("blocked").asBoolean());
        assertTrue(summary(next).path("reason").asText().contains("不能覆盖"));
        assertEquals("历史无SKU商品", jdbc.queryForObject("SELECT payload->>'category' FROM purchase_product WHERE id=?", String.class, generated));
    }

    @Test @org.junit.jupiter.api.Timeout(120)
    void serializesConcurrentConfirmedUploadsAndTheSecondSkipsInsteadOfDuplicatingNoSkuRow() throws Exception {
        var source = source();
        var first = job(source, true);
        var firstRow = stage(first, "采购", 2, values("", "并发无编号"), false);
        var second = job(source, true);
        var secondRow = stage(second, "采购", 2, values("", "并发无编号"), false);
        refresh(first);
        refresh(second);
        var written = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstPid = new AtomicInteger();
        var secondPid = new AtomicInteger();
        var before = productCount();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> tx.executeWithoutResult(ignored -> {
                firstPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                continuation.refresh(first);
                continuation.guardBatch(first, List.of(firstRow));
                imports.apply(first.id, List.of(firstRow), first.sourceHash);
                written.countDown();
                try { assertTrue(release.await(40, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
            }));
            assertTrue(written.await(30, TimeUnit.SECONDS));
            var two = executor.submit(() -> tx.executeWithoutResult(ignored -> {
                secondPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                secondStarted.countDown();
                apply(second, List.of(secondRow));
            }));
            try {
                assertTrue(secondStarted.await(20, TimeUnit.SECONDS));
                boolean waiting = false;
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!waiting && System.nanoTime() < deadline) {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                             WHERE pid=? AND wait_event_type='Lock' AND wait_event='advisory'
                               AND ? = ANY(pg_blocking_pids(pid)))
                            """, Boolean.class, secondPid.get(), firstPid.get()));
                    if (!waiting) Thread.sleep(25);
                }
                assertTrue(waiting,"The second upload must actually wait on the first source advisory lock");
            }
            finally { release.countDown(); }
            one.get(30, TimeUnit.SECONDS);
            two.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        }
        assertEquals(before + 1, productCount());
        assertEquals("history-skipped", status(secondRow));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM purchase_import_row WHERE job_id=? AND applied_at IS NOT NULL", Integer.class, second.id));
    }

    @Test void skuBackfillPreservesDisabledStateAndDoesNotMakeIncompleteProductsQuoteReady(){
        for(var disabled:List.of(false,true)){
            var source=source();var previous=job(source,true);var original=values("","保留商品");
            if(!disabled)original[8]="";
            var product=stage(previous,"采购",2,original,false);apply(previous,List.of(product));
            if(disabled)jdbc.update("UPDATE purchase_product SET catalog_state='disabled',quote_ready=false,payload=payload || '{\"catalogState\":\"disabled\",\"quoteReady\":false,\"notes\":\"人工备注\"}'::jsonb,version=version+1 WHERE id=?",product);
            var expectedWarnings=jdbc.queryForObject("SELECT jsonb_array_length(payload->'importWarnings') FROM purchase_product WHERE id=?",Integer.class,product);
            var next=job(source,true);original[0]=sku(source,2);var changed=stage(next,"采购",2,original,false);
            refresh(next);assertFalse(summary(next).path("blocked").asBoolean());assertEquals(1,summary(next).path("skuBackfillRows").asInt());
            assertEquals("sku-backfill",jdbc.queryForObject("SELECT import_action FROM purchase_import_row WHERE id=?",String.class,changed));
            apply(next,List.of(changed));
            assertEquals(product,jdbc.queryForObject("SELECT id FROM purchase_product WHERE sku=?",UUID.class,sku(source,2)));
            assertEquals(disabled?"disabled":"pending_template",jdbc.queryForObject("SELECT catalog_state FROM purchase_product WHERE id=?",String.class,product));
            assertFalse(jdbc.queryForObject("SELECT quote_ready FROM purchase_product WHERE id=?",Boolean.class,product));
            assertEquals("imported",jdbc.queryForObject("SELECT payload->>'skuOrigin' FROM purchase_product WHERE id=?",String.class,product));
            assertEquals(expectedWarnings-1,jdbc.queryForObject("SELECT jsonb_array_length(payload->'importWarnings') FROM purchase_product WHERE id=?",Integer.class,product));
            if(disabled)assertEquals("人工备注",jdbc.queryForObject("SELECT payload->>'notes' FROM purchase_product WHERE id=?",String.class,product));
        }
    }

    @Test void rejectsInvalidAndDuplicateBackfillTargetsWithoutMergingExistingProducts(){
        var source=source();var previous=job(source,true);
        var first=stage(previous,"采购",2,values("","商品A"),false);
        var second=stage(previous,"采购",3,values("","商品B"),false);apply(previous,List.of(first,second));
        for(var target:List.of("BAD SKU","TEST123","AUTO-BLOCKED", "X".repeat(97))){
            var next=job(source,true);var invalid=stage(next,"采购",2,values(target,"商品A"),false);
            stage(next,"采购",3,values("","商品B"),false);refresh(next);
            assertTrue(summary(next).path("blocked").asBoolean());assertEquals("error",status(invalid));
        }
        var duplicate=job(source,true);
        stage(duplicate,"采购",2,values(sku(source,9),"商品A"),false);
        stage(duplicate,"采购",3,values(sku(source,9),"商品B"),false);refresh(duplicate);
        assertTrue(summary(duplicate).path("blocked").asBoolean());
        assertTrue(summary(duplicate).path("reason").asText().contains("不能合并"));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM purchase_product WHERE id IN (?,?)",Integer.class,first,second));
    }

    @Test void backfillCannotChangeOtherSourceFieldsOrAnAlreadyFormalSku(){
        var source=source();var previous=job(source,true);
        var first=stage(previous,"采购",2,values("","原值"),false);apply(previous,List.of(first));
        var changed=job(source,true);stage(changed,"采购",2,values(sku(source,2),"偷偷改价或品类"),false);refresh(changed);
        assertTrue(summary(changed).path("blocked").asBoolean());
        var backfill=job(source,true);var row=stage(backfill,"采购",2,values(sku(source,2),"原值"),false);apply(backfill,List.of(row));
        var rekey=job(source,true);stage(rekey,"采购",2,values(sku(source,3),"原值"),false);refresh(rekey);
        assertTrue(summary(rekey).path("blocked").asBoolean());
        assertEquals(sku(source,2),jdbc.queryForObject("SELECT sku FROM purchase_product WHERE id=?",String.class,first));
    }

    @Test void backfillPreviewVersionIsNotSilentlyRebasedAndOccupiedTargetIsRejected(){
        var source=source();var previous=job(source,true);var first=stage(previous,"采购",2,values("","原值"),false);apply(previous,List.of(first));
        var next=job(source,true);var row=stage(next,"采购",2,values(sku(source,2),"原值"),false);refresh(next);
        var expected=jdbc.queryForObject("SELECT expected_version FROM purchase_import_row WHERE id=?",Long.class,row);
        jdbc.update("UPDATE purchase_product SET version=version+1,payload=payload || '{\"notes\":\"之后人工修改\"}'::jsonb WHERE id=?",first);
        refresh(next);assertTrue(summary(next).path("blocked").asBoolean());
        assertEquals(expected,jdbc.queryForObject("SELECT expected_version FROM purchase_import_row WHERE id=?",Long.class,row));
        var other=job(source(),false);var occupied=stage(other,"采购",2,values(sku(source,2),"占号商品"),true);apply(other,List.of(occupied));
        var fresh=job(source,true);stage(fresh,"采购",2,values(sku(source,2),"原值"),false);refresh(fresh);
        assertTrue(summary(fresh).path("blocked").asBoolean());assertTrue(summary(fresh).path("reason").asText().contains("占用"));
    }

    @Test void manuallyAssignedSameSkuSkipsButDifferentSkuOrMissingOriginalProductBlocksBackfill(){
        for(var state:List.of("same","different","missing")){
            var source=source();var previous=job(source,true);
            var product=stage(previous,"采购",2,values("","手工补号商品"),false);apply(previous,List.of(product));
            var target=sku(source,2);
            if("missing".equals(state))jdbc.update("DELETE FROM purchase_product WHERE id=?",product);
            else {
                var assigned="same".equals(state)?target:sku(source,3);
                jdbc.update("UPDATE purchase_product SET sku=?,payload=payload || jsonb_build_object('sku',?::text,'skuOrigin','manual','notes','人工备注'),version=version+1 WHERE id=?",assigned,assigned,product);
            }
            var before=productCount();
            var next=job(source,true);var row=stage(next,"采购",2,values(target,"手工补号商品"),false);refresh(next);
            if("same".equals(state)){
                assertFalse(summary(next).path("blocked").asBoolean());
                assertEquals("history-skipped",status(row));
                assertEquals(0,summary(next).path("pendingRows").asInt());
                apply(next,List.of(row));
                assertEquals(1L,jdbc.queryForObject("SELECT version FROM purchase_product WHERE id=?",Long.class,product));
                assertEquals("人工备注",jdbc.queryForObject("SELECT payload->>'notes' FROM purchase_product WHERE id=?",String.class,product));
            }else{
                assertTrue(summary(next).path("blocked").asBoolean());
                assertThrows(AppException.class,()->apply(next,List.of(row)));
                if("different".equals(state))assertEquals(sku(source,3),jdbc.queryForObject("SELECT sku FROM purchase_product WHERE id=?",String.class,product));
                else assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM purchase_product WHERE id=?",Integer.class,product));
            }
            assertEquals(before,productCount());
            assertNull(jdbc.queryForObject("SELECT applied_at FROM purchase_import_row WHERE id=?",java.sql.Timestamp.class,row));
        }
    }

    @Test @org.junit.jupiter.api.Timeout(90)
    void concurrentManualEditWinsOverBackfillCandidateSnapshot()throws Exception{
        var source=source();var previous=job(source,true);
        var product=stage(previous,"采购",2,values("","并发修改保护"),false);apply(previous,List.of(product));
        var next=job(source,true);var row=stage(next,"采购",2,values(sku(source,2),"并发修改保护"),false);refresh(next);
        var started=new CountDownLatch(1);var workerPid=new java.util.concurrent.atomic.AtomicInteger();
        var executor=Executors.newSingleThreadExecutor();
        try(var manual=pool.getConnection()){
            manual.setAutoCommit(false);
            int manualPid;
            try(var query=manual.createStatement();var rs=query.executeQuery("SELECT pg_backend_pid()")){rs.next();manualPid=rs.getInt(1);}
            try(var update=manual.prepareStatement("UPDATE purchase_product SET version=version+1,payload=payload || '{\"purchasePriceCny\":99,\"notes\":\"并发人工改价\"}'::jsonb,updated_at=now() WHERE id=?")){
                update.setObject(1,product);update.executeUpdate();
            }
            var future=executor.submit(()->tx.execute(ignored->{
                workerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                continuation.guardBatch(next,List.of(row));started.countDown();
                return imports.apply(next.id,List.of(row),next.sourceHash);
            }));
            try{
                assertTrue(started.await(20,TimeUnit.SECONDS));
                boolean waiting=false;
                for(int attempt=0;attempt<40&&!waiting;attempt++)waiting=Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT ? = ANY(pg_blocking_pids(?))",Boolean.class,manualPid,workerPid.get()));
                assertTrue(waiting,"Backfill should reach the product row lock with its older candidate snapshot");
                manual.commit();
                var result=future.get(30,TimeUnit.SECONDS);
                assertNotNull(result);assertEquals(0,result.applied());assertEquals(1,result.conflicts());
            }finally{manual.rollback();future.cancel(true);}
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));}
        assertEquals(99,jdbc.queryForObject("SELECT (payload->>'purchasePriceCny')::integer FROM purchase_product WHERE id=?",Integer.class,product));
        assertEquals("并发人工改价",jdbc.queryForObject("SELECT payload->>'notes' FROM purchase_product WHERE id=?",String.class,product));
        assertTrue(jdbc.queryForObject("SELECT sku LIKE 'AUTO-%' FROM purchase_product WHERE id=?",Boolean.class,product));
        assertEquals("conflict",status(row));
    }

    private static ImportJob job(String source, boolean append) {
        var job = new ImportJob();
        job.id = UUID.randomUUID(); job.jobType = AsyncPurchaseImportService.JOB_TYPE;
        job.status = "ready"; job.phase = "ready"; job.sourceName = source; job.sourceHash = "a".repeat(64);
        job.requestedBy = "ADMIN"; job.createdAt = Instant.now(); job.updatedAt = job.createdAt;
        job.payload = json.createObjectNode();
        if (append) PurchaseImportContinuationService.initialize(job);
        jdbc.update("""
                INSERT INTO import_job(id,job_type,status,requested_by,source_name,source_hash,payload,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?::jsonb,now(),now())
                """, job.id, job.jobType, job.status, job.requestedBy, source, job.sourceHash, job.payload.toString());
        return job;
    }

    private static UUID stage(ImportJob job, String sheet, int row, String[] values, boolean legacy) {
        var item = rowMapper.map(job.id.toString().substring(0, 8).toUpperCase(Locale.ROOT), 1, sheet, row,
                values, new PurchaseWorkbookSchema(PurchaseWorkbookSchema.Version.LEGACY));
        var id = UUID.randomUUID();
        var versions = jdbc.query("SELECT version FROM purchase_product WHERE sku=?", (rs, n) -> rs.getLong(1), item.sku());
        jdbc.update("""
                INSERT INTO purchase_import_row(id,job_id,source_sheet,source_row,sku,payload,source_content_hash,source_content_hash_without_sku,
                     created_at,validation_status,import_action,expected_version)
                VALUES (?,?,?,?,?,?::jsonb,?,?,now(),?,?,?)
                """, id, job.id, sheet, row, PurchaseImportBatchService.stagedSku(item), item.payload().toString(), legacy ? null : item.sourceContentHash(),legacy?null:item.sourceContentHashWithoutSku(),
                item.errors().isEmpty() ? "valid" : "error", item.errors().isEmpty() ? versions.isEmpty() ? "insert" : "update" : "skip",
                versions.isEmpty() ? null : versions.getFirst());
        return id;
    }

    private static void refresh(ImportJob job) {
        tx.executeWithoutResult(ignored -> {
            continuation.refresh(job);
            jdbc.update("UPDATE import_job SET payload=?::jsonb WHERE id=?", job.payload.toString(), job.id);
        });
    }
    private static void apply(ImportJob job, List<UUID> ids) {
        tx.executeWithoutResult(ignored -> {
            continuation.refresh(job);
            PurchaseImportContinuationService.requireUnblocked(job);
            continuation.guardBatch(job, ids);
            imports.apply(job.id, ids, job.sourceHash);
            continuation.recordRevision(job);
            jdbc.update("UPDATE import_job SET payload=?::jsonb WHERE id=?", job.payload.toString(), job.id);
        });
    }
    private static ObjectNode summary(ImportJob job) { return (ObjectNode) job.payload.path("continuation"); }
    private static String status(UUID row) { return jdbc.queryForObject("SELECT validation_status FROM purchase_import_row WHERE id=?", String.class, row); }
    private static long productCount() { return jdbc.queryForObject("SELECT count(*) FROM purchase_product", Long.class); }
    private static String source() { return UUID.randomUUID().toString().substring(0, 8) + ".xlsx"; }
    private static String sku(String source, int row) { return "SKU-" + source.substring(0, 8).toUpperCase(Locale.ROOT) + "-" + row; }
    private static String[] values(String sku, String category) {
        var values = new String[PurchaseWorkbookSchema.LEGACY_HEADERS.size()];
        Arrays.fill(values, "");
        values[0] = sku; values[1] = category; values[4] = "采购员"; values[5] = "2026-08-31";
        values[8] = "40"; values[12] = "1"; values[13] = "7.14";
        return values;
    }
}
