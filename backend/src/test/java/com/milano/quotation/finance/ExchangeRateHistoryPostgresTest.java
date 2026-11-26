package com.milano.quotation.finance;

import com.milano.quotation.security.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest @ActiveProfiles("test") @Testcontainers(disabledWithoutDocker=true)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ExchangeRateHistoryPostgresTest {
    @Container static final PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);r.add("spring.datasource.password",db::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto",()->"validate");r.add("spring.jpa.defer-datasource-initialization",()->"false");r.add("spring.flyway.enabled",()->"true");r.add("spring.sql.init.mode",()->"never");
    }
    @Autowired WebApplicationContext context; @Autowired JdbcClient jdbc; @Autowired ObjectMapper mapper;
    MockMvc mvc; MockHttpSession financeSession;
    @Autowired UserAccountRepository users; @Autowired PasswordEncoder encoder;
    @BeforeEach void setup() throws Exception {
        mvc=webAppContextSetup(context).apply(springSecurity()).build();
        if(!users.existsByAccountIgnoreCase("FINANCEQA"))users.saveAndFlush(UserAccount.create("FINANCEQA","财务测试人员",encoder.encode("ExchangeTest123"),"finance",false));
        financeSession=(MockHttpSession)mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json").content("{\"account\":\"FINANCEQA\",\"password\":\"ExchangeTest123\"}")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        jdbc.sql("delete from audit_log where resource_type='finance-setting' and resource_id='exchange-rate'").update();
        jdbc.sql("insert into finance_setting(setting_key,payload,version,updated_at) values('exchange-rate','{\"usdCny\":6.7,\"eurUsd\":1.15,\"updatedAt\":\"old\"}',0,now()) on conflict(setting_key) do update set payload=excluded.payload,version=0,updated_at=excluded.updated_at").update();
    }
    JsonNode save(String body,long version,int status) throws Exception {
        var response=mvc.perform(put("/api/v1/finance-settings/exchange-rate").session(financeSession).with(csrf()).header("If-Match",version).contentType("application/json").content(body))
            .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data");
    }
    JsonNode history(String query) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/finance-settings/exchange-rate/history"+query).session(financeSession)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void serverOwnsIdentityClockAndAtomicBeforeAfterAndIgnoresUnchangedSaves() throws Exception {
        var start=Instant.now().minusSeconds(1);
        var saved=save("{\"usdCny\":6.63,\"eurUsd\":1.16,\"updatedAt\":\"forged\",\"actorName\":\"forged\"}",0,200);
        assertFalse(saved.path("value").has("actorName"));assertTrue(Instant.parse(saved.path("value").path("updatedAt").asText()).isAfter(start));
        var data=history("");assertEquals(2,data.path("total").asInt());assertEquals(2,data.path("latest").size());
        var dollar=data.path("latest").path("usdCny");assertEquals(6.7,dollar.path("before").asDouble());assertEquals(6.63,dollar.path("after").asDouble());
        assertEquals("FINANCEQA",dollar.path("actorAccount").asText());assertEquals("财务测试人员",dollar.path("actorName").asText());assertTrue(Instant.parse(dollar.path("modifiedAt").asText()).isAfter(start));
        var same=save("{\"usdCny\":6.6300,\"eurUsd\":1.160,\"updatedAt\":\"spoof\"}",saved.path("_version").asLong(),200);
        assertEquals(saved,same);assertEquals(2,history("").path("total").asInt());
        save("{\"usdCny\":6.8,\"eurUsd\":1.16}",0,409);save("{\"usdCny\":0}",1,422);
        assertEquals(2,history("").path("total").asInt());
        var oldPage=save("{\"usdToCny\":6.8,\"updatedAt\":\"old client clock\"}",1,200);
        assertEquals(1.16,oldPage.path("value").path("eurUsd").asDouble());assertEquals(3,history("").path("total").asInt());
    }
    @Test void paginatesAndFiltersAndBoundsPageWithoutLosingOtherCurrencyLatest() throws Exception {
        for(int i=0;i<12;i++)save("{\"usdCny\":"+(7+i)+",\"eurUsd\":1.15}",i,200);
        save("{\"usdCny\":18,\"eurUsd\":1.16}",12,200);
        var first=history("?page=0");var second=history("?page=1");assertEquals(13,first.path("total").asInt());assertEquals(10,first.path("items").size());assertEquals(3,second.path("items").size());
        var ids=new HashSet<String>();for(var item:first.path("items"))assertTrue(ids.add(item.path("id").asText()));for(var item:second.path("items"))assertTrue(ids.add(item.path("id").asText()));
        var euro=history("?currency=eurUsd&page=99");assertEquals(1,euro.path("total").asInt());assertEquals(0,euro.path("page").asInt());assertEquals(2,euro.path("latest").size());
        mvc.perform(get("/api/v1/finance-settings/exchange-rate/history?currency=bad").session(financeSession)).andExpect(status().isUnprocessableEntity());
    }
    @Test void unauthorizedReadersAndWritersCannotAccessHistory() throws Exception {
        mvc.perform(get("/api/v1/finance-settings/exchange-rate/history").with(user("EMPLOYEE").authorities(()->"PERM_quotation"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/finance-settings/exchange-rate").with(user("EMPLOYEE").authorities(()->"PERM_quotation")).with(csrf()).header("If-Match",0).contentType("application/json").content("{\"usdCny\":7}")).andExpect(status().isForbidden());
        assertEquals(0,history("").path("total").asInt());
    }
    @Test void auditFailureRollsBackRateAndVersion() throws Exception {
        jdbc.sql("create function reject_exchange_audit() returns trigger language plpgsql as $$ begin if new.action='finance.exchange-rate.update' then raise exception 'test audit unavailable'; end if; return new; end $$").update();
        jdbc.sql("create trigger reject_exchange before insert on audit_log for each row execute function reject_exchange_audit()").update();
        try { save("{\"usdCny\":7}",0,500); }
        finally {jdbc.sql("drop trigger reject_exchange on audit_log").update();jdbc.sql("drop function reject_exchange_audit()").update();}
        assertEquals(0,jdbc.sql("select version from finance_setting where setting_key='exchange-rate'").query(Long.class).single());
        assertEquals("6.7",jdbc.sql("select payload->>'usdCny' from finance_setting where setting_key='exchange-rate'").query(String.class).single());assertEquals(0,history("").path("total").asInt());
    }
    @Test void concurrentEditorsProduceOneCommittedChange() throws Exception {
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=new ArrayList<Future<Integer>>();
            for(int n=0;n<2;n++){final int value=7+n;tasks.add(pool.submit(()->{gate.await();return mvc.perform(put("/api/v1/finance-settings/exchange-rate").session(financeSession).with(csrf()).header("If-Match",0).contentType("application/json").content("{\"usdCny\":"+value+"}")).andReturn().getResponse().getStatus();}));}
            gate.countDown();var statuses=new ArrayList<Integer>();for(var task:tasks)statuses.add(task.get(20,TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
        }
        assertEquals(1,history("").path("total").asInt());
    }
}
