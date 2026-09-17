package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class FinanceQuotationVersionsPostgresTest {
    @Container static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16.4-alpine");
    DriverManagerDataSource ds; JdbcClient jdbc; TransactionTemplate tx;
    @BeforeEach void setup() {
        ds=new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());jdbc=JdbcClient.create(ds);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.sql("create table if not exists finance_setting(setting_key text primary key,version bigint not null)").update();
        jdbc.sql("truncate finance_setting").update();
        for(var key:FinanceQuotationVersions.KEYS)jdbc.sql("insert into finance_setting values(:key,1)").param("key",key).update();
    }
    ObjectNode quote() {
        var q=new ObjectMapper().createObjectNode();var v=q.putObject("financeVersions");
        FinanceQuotationVersions.KEYS.forEach(k->v.put(k,1));return q;
    }
    @Test void rejectsEachOfSevenChangedSettingsAndMalformedVersions() {
        for(var key:FinanceQuotationVersions.KEYS) {
            var q=quote();((ObjectNode)q.path("financeVersions")).put(key,0);
            assertEquals(409,assertThrows(AppException.class,()->tx.executeWithoutResult(s->FinanceQuotationVersions.validate(jdbc,q))).status().value());
        }
        var bad=quote();((ObjectNode)bad.path("financeVersions")).remove("exchange-rate");
        assertEquals(422,assertThrows(AppException.class,()->FinanceQuotationVersions.validate(jdbc,bad)).status().value());
        assertDoesNotThrow(()->FinanceQuotationVersions.validate(jdbc,new ObjectMapper().createObjectNode()));
        assertDoesNotThrow(()->tx.executeWithoutResult(s->FinanceQuotationVersions.validate(jdbc,quote())));
    }
    @Test void financeUpdateCannotCommitBetweenVersionCheckAndQuotationCommit() throws Exception {
        var checked=new CountDownLatch(1);var release=new CountDownLatch(1);var updating=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var save=workers.submit(()->tx.executeWithoutResult(s->{FinanceQuotationVersions.validate(jdbc,quote());checked.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}}));
            assertTrue(checked.await(5,TimeUnit.SECONDS));
            var update=workers.submit(()->{updating.countDown();return jdbc.sql("update finance_setting set version=2 where setting_key='exchange-rate'").update();});
            assertTrue(updating.await(5,TimeUnit.SECONDS));
            try { assertThrows(TimeoutException.class,()->update.get(150,TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            save.get(5,TimeUnit.SECONDS);assertEquals(1,update.get(5,TimeUnit.SECONDS));
            assertEquals(409,assertThrows(AppException.class,()->tx.executeWithoutResult(s->FinanceQuotationVersions.validate(jdbc,quote()))).status().value());
        }
    }
}
