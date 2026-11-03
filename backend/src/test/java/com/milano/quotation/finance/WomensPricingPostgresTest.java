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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class WomensPricingPostgresTest {
    @Container static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16.4-alpine");
    final ObjectMapper mapper = new ObjectMapper();
    final WomensPricingTest fixture = new WomensPricingTest();
    JdbcClient jdbc; TransactionTemplate tx;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());
        jdbc=JdbcClient.create(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.sql("create table if not exists finance_setting(setting_key text primary key,payload jsonb not null,version bigint not null,updated_at timestamptz not null default now())").update();
        jdbc.sql("truncate finance_setting").update();
        jdbc.sql("insert into finance_setting(setting_key,payload,version) values('womens-pricing',cast(:p as jsonb),2),('exchange-rate','{\"usdCny\":7}',0)")
            .param("p",fixture.policy().toString()).update();
    }
    @Test void validatesRealJsonbAndPreservesOrdinaryQuotes() {
        var q=fixture.quote();var before=q.deepCopy();
        tx.executeWithoutResult(s->WomensPricing.validateCurrent(jdbc,mapper,q));assertEquals(before,q);
        jdbc.sql("delete from finance_setting where setting_key='womens-pricing'").update();
        assertDoesNotThrow(()->WomensPricing.validateCurrent(jdbc,mapper,mapper.createObjectNode().put("quoteMode","single")));
        assertThrows(AppException.class,()->WomensPricing.validateCurrent(jdbc,mapper,q));
    }
    @Test void migrationOnlySeedsMissingPolicyAndDoesNotOverwritePublishedChanges() throws Exception {
        String sql;
        try(var in=getClass().getResourceAsStream("/db/migration/V65__womens_pricing.sql")) { sql=new String(in.readAllBytes(),StandardCharsets.UTF_8); }
        jdbc.sql(sql).update();assertEquals(2,jdbc.sql("select version from finance_setting where setting_key='womens-pricing'").query(Integer.class).single());
        jdbc.sql("delete from finance_setting where setting_key='womens-pricing'").update();jdbc.sql(sql).update();
        var policy=mapper.readTree(jdbc.sql("select payload::text from finance_setting where setting_key='womens-pricing'").query(String.class).single());
        WomensPricing.validatePolicy(policy);assertEquals(fixture.policy(),policy);
        assertEquals(2,jdbc.sql("select count(*) from finance_setting").query(Integer.class).single());
    }
    @Test void publishCannotRaceWithQuoteCommitAndStaleQuoteIsRejected() throws Exception {
        var checked=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var save=workers.submit(()->tx.executeWithoutResult(s->{WomensPricing.validateCurrent(jdbc,mapper,fixture.quote());checked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}}));
            assertTrue(checked.await(10,TimeUnit.SECONDS));
            var update=workers.submit(()->{started.countDown();return jdbc.sql("update finance_setting set version=3 where setting_key='womens-pricing'").update();});
            assertTrue(started.await(5,TimeUnit.SECONDS));
            try { assertThrows(TimeoutException.class,()->update.get(200,TimeUnit.MILLISECONDS)); } finally { release.countDown(); }
            save.get(5,TimeUnit.SECONDS);assertEquals(1,update.get(5,TimeUnit.SECONDS));
            assertEquals(409,assertThrows(AppException.class,()->tx.executeWithoutResult(s->WomensPricing.validateCurrent(jdbc,mapper,fixture.quote()))).status().value());
        }
    }
}
