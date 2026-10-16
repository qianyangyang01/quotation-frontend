package com.milano.quotation.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class EmployeeRoleMigrationPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test void upgradesRoleCatalogWithoutChangingExistingAccountsPermissionsOrQuotations() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target("62").load().migrate();
        var jdbc = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        var userId = UUID.randomUUID();
        insertAccount(jdbc, userId, "ROLELEGACY", "employee");
        jdbc.sql("""
                insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at)
                values(:id,'ROLE-SNAPSHOT','ROLELEGACY','saved',cast(:payload as jsonb),3,now(),now())
                """).param("id", UUID.randomUUID()).param("payload", "{\"freight\":17.25,\"customerQuote\":28,\"financeReviewStatus\":\"approved\"}").update();
        var accountsBefore = jdbc.sql("select row_to_json(u)::text from app_user u order by account").query(String.class).list();
        var quotesBefore = jdbc.sql("select row_to_json(q)::text from quotation_record q order by id").query(String.class).list();
        var permissionsBefore = jdbc.sql("select role_key || ':' || permission_key from role_permission order by role_key,permission_key").query(String.class).list();

        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();

        assertEquals("业务-员工", jdbc.sql("select display_name from app_role where role_key='employee'").query(String.class).single());
        assertEquals("运营-员工", jdbc.sql("select display_name from app_role where role_key='operations_employee'").query(String.class).single());
        assertEquals(0, jdbc.sql("select count(*) from role_permission where role_key='operations_employee'").query(Integer.class).single());
        assertEquals(List.of("myRecords", "quote"), jdbc.sql("select permission_key from role_permission where role_key='employee' order by permission_key").query(String.class).list());
        assertEquals(permissionsBefore, jdbc.sql("select role_key || ':' || permission_key from role_permission order by role_key,permission_key").query(String.class).list());
        assertEquals(accountsBefore, jdbc.sql("select row_to_json(u)::text from app_user u order by account").query(String.class).list());
        assertEquals(quotesBefore, jdbc.sql("select row_to_json(q)::text from quotation_record q order by id").query(String.class).list());

        insertAccount(jdbc, UUID.randomUUID(), "ROLENEW", "operations_employee");
        assertEquals(1, jdbc.sql("update app_user set role_key='operations_employee' where id=:id").param("id", userId).update());
        assertEquals(2, jdbc.sql("select count(*) from app_user where role_key='operations_employee'").query(Integer.class).single());
    }

    private void insertAccount(JdbcClient jdbc, UUID id, String account, String role) {
        jdbc.sql("""
                insert into app_user(id,account,display_name,password_hash,role_key,status,must_change_password,password_updated_at,version,created_at,updated_at)
                values(:id,:account,'角色迁移测试','test-hash',:role,'enabled',false,now(),4,now(),now())
                """).param("id", id).param("account", account).param("role", role).update();
    }
}
