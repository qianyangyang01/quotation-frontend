package com.milano.quotation.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class FlywayPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("quotation_prod").withUsername("quotation_app").withPassword("quotation_test_password");

    @Test void appliesQuotationSchemaWithoutExternalDependencies() throws Exception {
        var baseline = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("11")).load();
        assertEquals(11, baseline.migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into customer (id, code, name, enabled, version, created_at, updated_at)
                    values ('11111111-1111-1111-1111-111111111111', 'LEGACY-1', '历史客户', true, 0, now(), now())
                    """);
            statement.executeUpdate("""
                    insert into quotation_record (id, quote_no, owner_account, status, payload, version, customer_id, created_at, updated_at)
                    values ('22222222-2222-2222-2222-222222222222', 'Q-LEGACY-1', 'ADMIN', 'pending',
                            '{"customerId":"11111111-1111-1111-1111-111111111111","customerGrade":"A级客户"}'::jsonb,
                            0, '11111111-1111-1111-1111-111111111111', now(), now())
                    """);
            statement.executeUpdate("""
                    insert into quotation_draft (owner_account, payload, version, updated_at)
                    values ('ADMIN', '{"customerId":"11111111-1111-1111-1111-111111111111","customerName":"草稿客户"}'::jsonb, 0, now())
                    """);
            statement.executeUpdate("""
                    insert into idempotency_record (id, account, operation, idempotency_key, request_hash, response_status, response_body, created_at)
                    values ('33333333-3333-3333-3333-333333333333', 'ADMIN', 'quotation-create', 'legacy-key', 'legacy-hash', 200,
                            '{"customerId":"11111111-1111-1111-1111-111111111111","customerName":"历史客户"}'::jsonb, now())
                    """);
            statement.executeUpdate("""
                    insert into purchase_product (id, sku, payload, catalog_state, quote_ready, version, created_at, updated_at)
                    values ('44444444-4444-4444-4444-444444444444', 'BIZ-NO-DIMENSIONS',
                            '{"weightG":180,"minOrderQty":1,"purchasePriceCny":36.8,"quoteReady":false,"status":"待补充资料"}'::jsonb,
                            'ready', false, 0, now(), now())
                    """);
        }
        var flyway = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("15")).load();
        var migrationResult = flyway.migrate();
        assertEquals(4, migrationResult.migrationsExecuted);
        assertEquals(true, migrationResult.migrations.stream().anyMatch(item -> "13".equals(item.version)));
        assertEquals(true, migrationResult.migrations.stream().anyMatch(item -> "14".equals(item.version)));
        assertEquals(true, migrationResult.migrations.stream().anyMatch(item -> "15".equals(item.version)));
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name in ('app_user','purchase_product','quotation_record','audit_log','customer','supplier','quotation_share','business_migration_batch')");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(7, result.getInt(1));
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("select payload, exists (select 1 from information_schema.columns where table_schema='public' and table_name='quotation_record' and column_name='customer_id') from quotation_record where quote_no='Q-LEGACY-1'");
             var result = statement.executeQuery()) {
            result.next();
            var payload = result.getString(1);
            assertFalse(result.getBoolean(2));
            assertFalse(payload.contains("customerId"));
            assertEquals(true, payload.contains("历史客户"));
            assertEquals(true, payload.contains("A级客户"));
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("select payload from quotation_draft where owner_account='ADMIN'");
             var result = statement.executeQuery()) {
            result.next();
            assertFalse(result.getString(1).contains("customerId"));
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("select response_body from idempotency_record where idempotency_key='legacy-key'");
             var result = statement.executeQuery()) {
            result.next();
            assertFalse(result.getString(1).contains("customerId"));
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name='purchase_product' and column_name in ('catalog_state','quote_ready','source_hash')");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(3, result.getInt(1));
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("select quote_ready, payload ->> 'quoteReady', payload ->> 'status' from purchase_product where sku='BIZ-NO-DIMENSIONS'");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(true, result.getBoolean(1));
            assertEquals("true", result.getString(2));
            assertEquals("资料完整", result.getString(3));
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
              var statement = connection.prepareStatement("select data_type from information_schema.columns where table_schema='public' and table_name='purchase_product' and column_name='source_hash'");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals("character varying", result.getString(1));
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("select count(*) from information_schema.columns where table_schema='public' and table_name='logistics_channel' and column_name in ('archived_at','archived_by','archive_reason')");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(3, result.getInt(1));
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     select count(*)
                     from information_schema.columns
                     where table_schema = 'public'
                       and data_type = 'character varying'
                       and (table_name, column_name) in (
                           ('asset_object', 'sha256'),
                           ('import_part', 'sha256'),
                           ('migration_manifest_entry', 'expected_sha256'),
                           ('idempotency_record', 'request_hash')
                       )
                     """);
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(4, result.getInt(1));
        }

        seedPurchaseProductsForCategoryMigration();
        var categoryMigration = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("16")).load().migrate();
        assertEquals(1, categoryMigration.migrationsExecuted);
        assertEquals(true, categoryMigration.migrations.stream().anyMatch(item -> "16".equals(item.version)));
        assertPurchaseCategoryMigration();
        restorePurchaseCategories();
        assertPurchaseCategoriesRestored();

        seedQuotationRemovalMigration();
        var quotationRemovalMigration = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("17")).load().migrate();
        assertEquals(1, quotationRemovalMigration.migrationsExecuted);
        assertEquals(true, quotationRemovalMigration.migrations.stream().anyMatch(item -> "17".equals(item.version)));
        assertQuotationRemovalMigration();
        restoreQuotationVoidState();
        assertQuotationVoidStateRestored();

        seedSupplierRemovalMigration();
        var supplierRecordMigrations = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("23")).load().migrate();
        assertEquals(6, supplierRecordMigrations.migrationsExecuted);
        assertEquals(true, supplierRecordMigrations.migrations.stream().anyMatch(item -> "18".equals(item.version)));
        assertEquals(true, supplierRecordMigrations.migrations.stream().anyMatch(item -> "19".equals(item.version)));
        assertEquals(true, supplierRecordMigrations.migrations.stream().anyMatch(item -> "20".equals(item.version)));
        assertEquals(true, supplierRecordMigrations.migrations.stream().anyMatch(item -> "21".equals(item.version)));
        assertEquals(true, supplierRecordMigrations.migrations.stream().anyMatch(item -> "22".equals(item.version)));
        assertEquals(true, supplierRecordMigrations.migrations.stream().anyMatch(item -> "23".equals(item.version)));
        seedSupplierRecordBeforeStructuredScoring();
        var finalMigrations = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        assertEquals(4, finalMigrations.migrationsExecuted);
        assertEquals(true, finalMigrations.migrations.stream().anyMatch(item -> "27".equals(item.version)));
        assertEquals(true, finalMigrations.migrations.stream().anyMatch(item -> "26".equals(item.version)));
        assertEquals(true, finalMigrations.migrations.stream().anyMatch(item -> "25".equals(item.version)));
        assertEquals(true, finalMigrations.migrations.stream().anyMatch(item -> "24".equals(item.version)));
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     select count(*) from pg_indexes
                     where schemaname = 'public'
                       and indexname in ('idx_purchase_product_sku_trgm', 'idx_purchase_product_payload_trgm')
                     """);
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(2, result.getInt(1));
        }
        assertPurchaseImportRowsAreUniqueWithinSheet();
        assertSupplierRemovalMigration();
        assertSupplierRecordStructuredScoringMigration();
    }

    private void assertPurchaseImportRowsAreUniqueWithinSheet() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into import_job(id, job_type, status, requested_by, source_name, payload, created_at, updated_at)
                    values ('21111111-1111-1111-1111-111111111111', 'purchase-xlsx-async', 'ready', 'ADMIN',
                            'multi-sheet.xlsx', '{}', now(), now())
                    """);
            statement.executeUpdate("""
                    insert into purchase_import_row(id, job_id, source_sheet, source_row, sku, payload, created_at, validation_status)
                    values ('21222222-2222-2222-2222-222222222222', '21111111-1111-1111-1111-111111111111',
                            '工作表一', 2, 'SKU-SHEET-1', '{}', now(), 'valid')
                    """);
            statement.executeUpdate("""
                    insert into purchase_import_row(id, job_id, source_sheet, source_row, sku, payload, created_at, validation_status)
                    values ('21333333-3333-3333-3333-333333333333', '21111111-1111-1111-1111-111111111111',
                            '工作表二', 2, 'SKU-SHEET-2', '{}', now(), 'valid')
                    """);
            try (var result = statement.executeQuery("""
                    select count(*) from purchase_import_row
                    where job_id = '21111111-1111-1111-1111-111111111111' and source_row = 2
                    """)) {
                result.next();
                assertEquals(2, result.getInt(1));
            }
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    insert into purchase_import_row(id, job_id, source_sheet, source_row, sku, payload, created_at, validation_status)
                    values ('21444444-4444-4444-4444-444444444444', '21111111-1111-1111-1111-111111111111',
                            '工作表一', 2, 'SKU-SHEET-DUPLICATE', '{}', now(), 'valid')
                    """));
        }
    }

    private void seedSupplierRemovalMigration() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into supplier(id, code, name, enabled, version, created_at, updated_at)
                    values ('88888888-8888-8888-8888-888888888888', 'SUP-REMOVE-1', '待移除供应商', true, 0, now(), now())
                    """);
            statement.executeUpdate("""
                    insert into supplier_product(id, supplier_id, product_id, supplier_sku, enabled, created_at, updated_at)
                    values ('99999999-9999-9999-9999-999999999999',
                            '88888888-8888-8888-8888-888888888888',
                            '44444444-4444-4444-4444-444444444444', 'LEGACY-SKU', true, now(), now())
                    """);
            statement.executeUpdate("""
                    insert into audit_log(id, request_id, actor_account, action, resource_type, resource_id, outcome, detail, created_at)
                    values ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'request-v19', 'ADMIN', 'supplier.update',
                            'supplier', '88888888-8888-8888-8888-888888888888', 'success', '{"marker":"supplier-audit-kept"}'::jsonb, now())
                    """);
        }
    }

    private void assertSupplierRemovalMigration() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("select to_regclass('public.supplier') is null, to_regclass('public.supplier_product') is null")) {
                result.next(); assertEquals(true, result.getBoolean(1)); assertEquals(true, result.getBoolean(2));
            }
            try (var result = statement.executeQuery("select to_regclass('public.supplier_record') is not null")) {
                result.next(); assertEquals(true, result.getBoolean(1));
            }
            try (var result = statement.executeQuery("""
                    select count(*)
                    from information_schema.table_constraints
                    where table_schema='public' and table_name='supplier_record'
                      and constraint_type='FOREIGN KEY'
                    """)) {
                result.next(); assertEquals(1, result.getInt(1));
            }
            try (var result = statement.executeQuery("""
                    select count(*) from information_schema.columns
                    where table_schema='public' and table_name='supplier_record'
                      and column_name in ('boss_name','contact_details','corporate_account','corporate_bank','business_license_asset_id')
                    """)) {
                result.next(); assertEquals(5, result.getInt(1));
            }
            try (var result = statement.executeQuery("""
                    select count(*) from information_schema.columns
                    where table_schema='public' and table_name='supplier_record'
                      and column_name in ('contact_role','relationship_notes','cost_sheet')
                    """)) {
                result.next(); assertEquals(0, result.getInt(1));
            }
            try (var result = statement.executeQuery("select count(*) from purchase_product where id='44444444-4444-4444-4444-444444444444'")) {
                result.next(); assertEquals(1, result.getInt(1));
            }
            try (var result = statement.executeQuery("select count(*), min(detail->>'marker') from audit_log where id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'")) {
                result.next(); assertEquals(1, result.getInt(1)); assertEquals("supplier-audit-kept", result.getString(2));
            }
        }
    }

    private void seedSupplierRecordBeforeStructuredScoring() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into supplier_record(
                        id, name, invoice_type, tax_point, after_sales, cooperation_score, rating,
                        created_by, updated_by, version, created_at, updated_at
                    ) values (
                        'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'V23历史供应商', '不开票', 0.03,
                        '历史售后自由文本', 88, 'B级', 'ADMIN', 'ADMIN', 0, now(), now()
                    )
                    """);
        }
    }

    private void assertSupplierRecordStructuredScoringMigration() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("""
                    select count(*) from information_schema.columns
                    where table_schema='public' and table_name='supplier_record'
                      and column_name in ('price_level','after_sales_available','calculated_score','score_policy_version')
                    """)) {
                result.next(); assertEquals(4, result.getInt(1));
            }
            try (var result = statement.executeQuery("""
                    select invoice_type, tax_point, after_sales, cooperation_score,
                           price_level, after_sales_available, calculated_score, score_policy_version
                    from supplier_record where id='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
                    """)) {
                result.next();
                assertEquals("不开票", result.getString(1));
                assertEquals(0, new java.math.BigDecimal("0.030000").compareTo(result.getBigDecimal(2)));
                assertEquals("历史售后自由文本", result.getString(3));
                assertEquals(88, result.getInt(4));
                assertEquals(null, result.getString(5));
                assertEquals(null, result.getObject(6));
                assertEquals(null, result.getObject(7));
                assertEquals(null, result.getString(8));
            }
        }
    }

    private void seedPurchaseProductsForCategoryMigration() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                     insert into purchase_product(
                         id, sku, payload, version, created_at, updated_at,
                         catalog_state, quote_ready
                     ) values (?, ?, ?::jsonb, ?, now(), now(), ?, ?)
                     """)) {
            // Together with BIZ-NO-DIMENSIONS seeded above this yields exactly
            // 54 rows, allowing two deterministic assignments per category.
            for (var index = 0; index < 53; index++) {
                var sku = "CATEGORY-%03d".formatted(index);
                var payload = index == 0
                        ? "{\"sku\":\"%s\",\"marker\":\"unchanged\"}".formatted(sku)
                        : "{\"sku\":\"%s\",\"category\":\"旧品类-%d\",\"marker\":\"unchanged\"}".formatted(sku, index);
                statement.setObject(1, java.util.UUID.nameUUIDFromBytes(sku.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                statement.setString(2, sku);
                statement.setString(3, payload);
                statement.setLong(4, index);
                statement.setString(5, switch (index % 3) {
                    case 0 -> "ready";
                    case 1 -> "disabled";
                    default -> "pending_template";
                });
                statement.setBoolean(6, index % 3 == 0);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void assertPurchaseCategoryMigration() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("select count(*) from purchase_product_category_backup_v16")) {
                result.next(); assertEquals(54, result.getInt(1));
            }
            try (var result = statement.executeQuery("""
                    select count(*), min(category_count), max(category_count)
                    from (
                        select payload->>'category' as category, count(*) as category_count
                        from purchase_product
                        group by payload->>'category'
                    ) categories
                    """)) {
                result.next(); assertEquals(27, result.getInt(1)); assertEquals(2, result.getInt(2)); assertEquals(2, result.getInt(3));
            }
            try (var result = statement.executeQuery("""
                    select count(*)
                    from purchase_product
                    where payload->>'category' not in (
                        '文胸','袜子','内裤','服装','化妆品','保健品','日用品','庭院工具','家用电器',
                        '健身器材','厨房用具','家纺','配饰','鞋','文具','灯具','数码','辅料','玩具','书籍',
                        '宠物用品','医疗','汽车用品','清洁用品','箱包','护肤品','其他'
                    )
                       or payload->>'marker' <> 'unchanged'
                    """)) {
                result.next(); assertEquals(0, result.getInt(1));
            }
            try (var result = statement.executeQuery("""
                    select count(*)
                    from purchase_product product
                    join purchase_product_category_backup_v16 backup on backup.product_id = product.id
                    where product.version <> backup.previous_version + 1
                    """)) {
                result.next(); assertEquals(0, result.getInt(1));
            }
            try (var result = statement.executeQuery("select count(*) from purchase_product_category_backup_v16 where not had_category and previous_category is null")) {
                result.next(); assertEquals(2, result.getInt(1));
            }
        }
    }

    private void restorePurchaseCategories() throws Exception {
        var workingDirectory = Path.of("").toAbsolutePath();
        var script = workingDirectory.resolve("deploy/scripts/rollback-purchase-categories-v16.sql");
        if (!Files.exists(script)) script = workingDirectory.resolve("../deploy/scripts/rollback-purchase-categories-v16.sql").normalize();
        var rollbackSql = Files.readString(script);
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(rollbackSql);
            assertThrows(java.sql.SQLException.class, () -> statement.execute(rollbackSql));
        }
    }

    private void assertPurchaseCategoriesRestored() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("select count(*) from purchase_product_category_backup_v16 where restored_at is not null")) {
                result.next(); assertEquals(54, result.getInt(1));
            }
            try (var result = statement.executeQuery("select count(*) from purchase_product where not (payload ? 'category')")) {
                result.next(); assertEquals(2, result.getInt(1));
            }
            try (var result = statement.executeQuery("select count(*) from purchase_product where payload->>'category' like '旧品类-%' and payload->>'marker' = 'unchanged'")) {
                result.next(); assertEquals(52, result.getInt(1));
            }
            try (var result = statement.executeQuery("""
                    select count(*)
                    from purchase_product product
                    join purchase_product_category_backup_v16 backup on backup.product_id = product.id
                    where product.version <> backup.previous_version + 2
                    """)) {
                result.next(); assertEquals(0, result.getInt(1));
            }
        }
    }

    @Test void refusesToGuessMissingPreVoidStatus() throws Exception {
        var schema = "invalid_void_mapping";
        var baseline = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("16")).load();
        baseline.migrate();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            statement.executeUpdate("""
                    insert into quotation_record(id, quote_no, owner_account, status, payload, version, voided_at, created_at, updated_at)
                    values ('66666666-6666-6666-6666-666666666666', 'Q-INVALID-VOID', 'ADMIN', 'voided',
                            '{"status":"voided","customerName":"缺失映射"}'::jsonb, 0, now(), now(), now())
                    """);
        }
        var migration = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load();
        assertThrows(org.flywaydb.core.api.FlywayException.class, migration::migrate);
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            try (var result = statement.executeQuery("select status, payload ? '_statusBeforeVoid', voided_at is not null from quotation_record where quote_no='Q-INVALID-VOID'")) {
                result.next(); assertEquals("voided", result.getString(1)); assertEquals(false, result.getBoolean(2)); assertEquals(true, result.getBoolean(3));
            }
        }
    }

    private void seedQuotationRemovalMigration() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    insert into quotation_record(
                        id, quote_no, owner_account, status, payload, version,
                        voided_at, voided_by, void_reason, created_at, updated_at
                    ) values (
                        '55555555-5555-5555-5555-555555555555', 'Q-VOIDED-1', 'ADMIN', 'voided',
                        '{"status":"voided","_statusBeforeVoid":"won","customerName":"保留客户","revisions":[{"id":"revision-kept"}]}'::jsonb,
                        7, '2026-08-24T01:02:03Z', 'ADMIN', '历史作废原因',
                        '2026-08-20T01:00:00Z', '2026-08-24T01:02:03Z'
                    )
                    """);
            for (var index = 0; index < 3; index++) {
                statement.executeUpdate("""
                        insert into quotation_share(id, quotation_id, token_hash, created_by, expires_at, created_at)
                        values (
                            gen_random_uuid(),
                            '55555555-5555-5555-5555-555555555555',
                            md5(random()::text) || md5(random()::text),
                            'ADMIN', now() + interval '7 days', now()
                        )
                        """);
            }
            statement.executeUpdate("""
                    insert into audit_log(id, request_id, actor_account, action, resource_type, resource_id, outcome, detail, created_at)
                    values ('77777777-7777-7777-7777-777777777777', 'request-v17', 'ADMIN', 'quotation.void',
                            'quotation', '55555555-5555-5555-5555-555555555555', 'success', '{"marker":"audit-kept"}'::jsonb, now())
                    """);
        }
    }

    private void assertQuotationRemovalMigration() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("select to_regclass('public.quotation_share') is null")) {
                result.next(); assertEquals(true, result.getBoolean(1));
            }
            try (var result = statement.executeQuery("""
                    select count(*)
                    from information_schema.columns
                    where table_schema='public' and table_name='quotation_record'
                      and column_name in ('voided_at','voided_by','void_reason')
                    """)) {
                result.next(); assertEquals(0, result.getInt(1));
            }
            try (var result = statement.executeQuery("""
                    select status, payload->>'status', payload ? '_statusBeforeVoid',
                           payload->>'customerName', payload->'revisions'->0->>'id', version,
                           updated_at = '2026-08-24T01:02:03Z'::timestamptz
                    from quotation_record where quote_no='Q-VOIDED-1'
                    """)) {
                result.next();
                assertEquals("won", result.getString(1));
                assertEquals("won", result.getString(2));
                assertEquals(false, result.getBoolean(3));
                assertEquals("保留客户", result.getString(4));
                assertEquals("revision-kept", result.getString(5));
                assertEquals(7, result.getLong(6));
                assertEquals(true, result.getBoolean(7));
            }
            try (var result = statement.executeQuery("""
                    select previous_status, previous_status_before_void #>> '{}', normalized_status,
                           previous_voided_by, previous_void_reason, previous_version
                    from quotation_void_state_backup_v17
                    """)) {
                result.next();
                assertEquals("voided", result.getString(1));
                assertEquals("won", result.getString(2));
                assertEquals("won", result.getString(3));
                assertEquals("ADMIN", result.getString(4));
                assertEquals("历史作废原因", result.getString(5));
                assertEquals(7, result.getLong(6));
                assertEquals(false, result.next());
            }
            try (var result = statement.executeQuery("select count(*) from quotation_record")) {
                result.next(); assertEquals(2, result.getInt(1));
            }
            try (var result = statement.executeQuery("select count(*), min(detail->>'marker') from audit_log where id='77777777-7777-7777-7777-777777777777'")) {
                result.next(); assertEquals(1, result.getInt(1)); assertEquals("audit-kept", result.getString(2));
            }
        }
    }

    private void restoreQuotationVoidState() throws Exception {
        var workingDirectory = Path.of("").toAbsolutePath();
        var script = workingDirectory.resolve("deploy/scripts/restore-quotation-void-state-v17.sql");
        if (!Files.exists(script)) script = workingDirectory.resolve("../deploy/scripts/restore-quotation-void-state-v17.sql").normalize();
        var restoreSql = Files.readString(script);
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(restoreSql);
            assertThrows(java.sql.SQLException.class, () -> statement.execute(restoreSql));
        }
    }

    private void assertQuotationVoidStateRestored() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("""
                    select status, payload->>'status', payload->>'_statusBeforeVoid',
                           voided_at = '2026-08-24T01:02:03Z'::timestamptz,
                           voided_by, void_reason, version, payload->'revisions'->0->>'id'
                    from quotation_record where quote_no='Q-VOIDED-1'
                    """)) {
                result.next();
                assertEquals("voided", result.getString(1));
                assertEquals("voided", result.getString(2));
                assertEquals("won", result.getString(3));
                assertEquals(true, result.getBoolean(4));
                assertEquals("ADMIN", result.getString(5));
                assertEquals("历史作废原因", result.getString(6));
                assertEquals(7, result.getLong(7));
                assertEquals("revision-kept", result.getString(8));
            }
        }
    }
}
