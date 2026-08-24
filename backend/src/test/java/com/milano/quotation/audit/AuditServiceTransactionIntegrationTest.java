package com.milano.quotation.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:audit-transaction;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=500"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuditServiceTransactionIntegrationTest {
    @Autowired AuditService audit;
    @Autowired AuditLogRepository logs;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;

    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactions);
        logs.deleteAll();
    }

    @Test
    void joinsBusinessTransactionWithoutRequestingASecondConnection() throws SQLException {
        try (var reservedConnection = dataSource.getConnection()) {
            assertTimeout(Duration.ofSeconds(2), () -> transaction.executeWithoutResult(status ->
                    audit.record("quotation.create", "quotation", "quote-1", "success", Map.of())));
        }

        assertEquals(1, logs.count());
    }

    @Test
    void successfulAuditRollsBackWithBusinessTransaction() {
        transaction.executeWithoutResult(status -> {
            audit.record("quotation.create", "quotation", "quote-rollback", "success", Map.of());
            status.setRollbackOnly();
        });

        assertEquals(0, logs.count());
    }

    @Test
    void persistsWhenThereIsNoOuterTransaction() {
        audit.record("auth.login", "session", "ADMIN", "failure", Map.of("reason", "invalid-credentials"));

        assertEquals(1, logs.count());
    }
}
