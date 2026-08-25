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
                .locations("classpath:db/migration").load().migrate();
        assertEquals(1, categoryMigration.migrationsExecuted);
        assertEquals(true, categoryMigration.migrations.stream().anyMatch(item -> "16".equals(item.version)));
        assertPurchaseCategoryMigration();
        restorePurchaseCategories();
        assertPurchaseCategoriesRestored();
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
}
