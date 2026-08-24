package com.milano.quotation.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        }
        var flyway = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load();
        var migrationResult = flyway.migrate();
        assertEquals(2, migrationResult.migrationsExecuted);
        assertEquals(true, migrationResult.migrations.stream().anyMatch(item -> "13".equals(item.version)));
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
    }
}
