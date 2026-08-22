package com.milano.quotation.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class FlywayPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("quotation_prod").withUsername("quotation_app").withPassword("quotation_test_password");

    @Test void appliesQuotationSchemaWithoutExternalDependencies() throws Exception {
        var flyway = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load();
        assertEquals(5, flyway.migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("select count(*) from information_schema.tables where table_schema='public' and table_name in ('app_user','purchase_product','quotation_record','audit_log')");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(4, result.getInt(1));
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
