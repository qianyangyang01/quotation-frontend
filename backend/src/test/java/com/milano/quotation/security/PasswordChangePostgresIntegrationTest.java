package com.milano.quotation.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class PasswordChangePostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("quotation_prod")
            .withUsername("quotation_app")
            .withPassword("quotation_test_password");

    @Test
    void persistsPasswordHistoryWithPostgresTimestampBinding() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = JdbcClient.create(dataSource);
        var account = UserAccount.create("PGUSER", "Postgres User", "old-hash", "employee", true);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        insert into app_user(id,account,display_name,password_hash,role_key,status,must_change_password,
                                             password_updated_at,version,created_at,updated_at)
                        values(:id,:account,:name,:hash,:role,:status,:mustChange,:passwordUpdated,0,:created,:updated)
                        """)
                .param("id", account.id)
                .param("account", account.account)
                .param("name", account.displayName)
                .param("hash", account.passwordHash)
                .param("role", account.roleKey)
                .param("status", account.status)
                .param("mustChange", account.mustChangePassword)
                .param("passwordUpdated", now)
                .param("created", now)
                .param("updated", now)
                .update();

        var users = mock(UserAccountRepository.class);
        var encoder = mock(PasswordEncoder.class);
        when(users.findByAccountIgnoreCase("PGUSER")).thenReturn(Optional.of(account));
        when(encoder.matches("OldPass123", "old-hash")).thenReturn(true);
        when(encoder.encode("NewPass4567")).thenReturn("new-hash");

        new UserAccountService(users, jdbc, encoder).changePassword("pguser", "OldPass123", "NewPass4567");

        assertFalse(account.mustChangePassword);
        assertEquals(1, jdbc.sql("select count(*) from password_change_history where user_id = :id")
                .param("id", account.id)
                .query(Integer.class)
                .single());
    }
}
