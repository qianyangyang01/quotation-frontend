package com.milano.quotation.quote;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Run the same ownership, idempotency and concurrent-admin contract on the production DB engine. */
@Testcontainers(disabledWithoutDocker = true)
class QuotationTemplateSyncPostgresTest extends QuotationTemplateSyncIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("spring.flyway.enabled", () -> true);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.jpa.defer-datasource-initialization", () -> false);
        properties.add("spring.sql.init.mode", () -> "never");
    }
}
