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
        jdbc.sql("create table if not exists audit_log(id uuid primary key,request_id text,actor_account text,action text,resource_type text,resource_id text,outcome text,detail jsonb,created_at timestamptz)").update();
        jdbc.sql("truncate finance_setting,audit_log").update();
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
        assertEquals(1,policy.path("schemaVersion").asInt());assertEquals(fixture.policy().path("tiers"),policy.path("tiers"));
        migrateReference();
        var current=mapper.readTree(jdbc.sql("select payload::text from finance_setting where setting_key='womens-pricing'").query(String.class).single());
        WomensPricing.validatePolicy(current);assertEquals(fixture.policy(),current);
        assertEquals(2,jdbc.sql("select count(*) from finance_setting").query(Integer.class).single());
    }
    void migrateReference() throws Exception {
        try(var in=getClass().getResourceAsStream("/db/migration/V66__womens_reference_pricing.sql")) {
            jdbc.sql(new String(in.readAllBytes(),StandardCharsets.UTF_8)).update();
        }
    }
    @Test void referenceMigrationPreservesCustomizedTiersDisabledStateAndLegacyHistoryAndIsIdempotent() throws Exception {
        var old=fixture.policy().put("schemaVersion",1).put("basis","total-cost-usd").put("enabled",false);old.remove("referenceCoefficient");
        ((tools.jackson.databind.node.ObjectNode)old.path("tiers").get(0)).put("coefficient",1.29);
        jdbc.sql("update finance_setting set payload=cast(:p as jsonb),version=7 where setting_key='womens-pricing'").param("p",old.toString()).update();
        jdbc.sql("create table if not exists quotation_record(id int primary key,payload jsonb,status text,version bigint)").update();
        jdbc.sql("insert into quotation_record values(1,cast(:p as jsonb),'won',9)").param("p",fixture.quote().toString()).update();
        var history=jdbc.sql("select to_jsonb(q)::text from quotation_record q").query(String.class).single();
        migrateReference();
        var row=mapper.readTree(jdbc.sql("select jsonb_build_object('payload',payload,'version',version)::text from finance_setting where setting_key='womens-pricing'").query(String.class).single());
        assertEquals(8,row.path("version").asInt());assertEquals(old.path("tiers"),row.path("payload").path("tiers"));
        assertFalse(row.path("payload").path("enabled").asBoolean());assertEquals(1.23,row.path("payload").path("referenceCoefficient").asDouble());
        var audit=mapper.readTree(jdbc.sql("select detail::text from audit_log").query(String.class).single());
        assertEquals(old,mapper.readTree(audit.path("before").asText()));assertEquals(row.path("payload"),mapper.readTree(audit.path("after").asText()));
        assertEquals(history,jdbc.sql("select to_jsonb(q)::text from quotation_record q").query(String.class).single());
        migrateReference();assertEquals(1,jdbc.sql("select count(*) from audit_log").query(Integer.class).single());
        assertEquals(8,jdbc.sql("select version from finance_setting where setting_key='womens-pricing'").query(Integer.class).single());
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
