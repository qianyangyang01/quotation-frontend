package com.milano.quotation.quote;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Run the same concurrent API/locking scenarios against the production database engine. */
@Testcontainers(disabledWithoutDocker=true)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class QuotationReviewPostgresIntegrationTest extends QuotationFinanceReviewIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.sql.init.mode",()->"never");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("spring.jpa.defer-datasource-initialization",()->"false");r.add("spring.flyway.enabled",()->"true");
    }
}
