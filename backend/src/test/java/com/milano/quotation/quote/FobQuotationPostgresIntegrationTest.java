package com.milano.quotation.quote;

import com.milano.quotation.security.*;
import com.milano.quotation.fob.FobPurchaseService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest @ActiveProfiles("test") @Testcontainers(disabledWithoutDocker=true)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class FobQuotationPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.sql.init.mode",()->"never");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");r.add("spring.jpa.defer-datasource-initialization",()->"false");r.add("spring.flyway.enabled",()->"true");
    }
    @Autowired WebApplicationContext context;@Autowired UserAccountRepository users;@Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;@Autowired JdbcClient jdbc;@Autowired FobPurchaseService purchases;
    MockMvc mvc;Map<String,MockHttpSession> sessions;ObjectNode input;
    @BeforeEach void setup() throws Exception {
        mvc=webAppContextSetup(context).apply(springSecurity()).build();sessions=new HashMap<>();
        String suffix=UUID.randomUUID().toString().substring(0,8).toUpperCase();
        for(String role:List.of("super_admin","finance","employee","employee2","purchase")) {
            var account=role.toUpperCase()+suffix;users.saveAndFlush(UserAccount.create(account,role,encoder.encode("FobSaveTest123"),role.equals("employee2")?"employee":role,false));
            var login=mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json").content(mapper.createObjectNode().put("account",account).put("password","FobSaveTest123").toString())).andExpect(status().isOk()).andReturn();
            sessions.put(role,(MockHttpSession)login.getRequest().getSession(false));
        }
        input=FobQuotationTest.input();
        jdbc.sql("insert into finance_setting(setting_key,payload,version,updated_at) values('exchange-rate',cast(:p as jsonb),0,now()) on conflict(setting_key) do update set payload=excluded.payload,version=finance_setting.version+1").param("p","{\"usdCny\":6.7}").update();
        var source=mapper.createObjectNode().put("sku","FOB-SAVE").put("category","内裤").put("weightRaw","60 g");source.set("parsed",input.at("/fob/product/parsed"));
        jdbc.sql("insert into fob_purchase_product(sku,payload,version,created_at,updated_at) values('FOB-SAVE',cast(:p as jsonb),0,now(),'2026-10-09T00:00:00Z') on conflict(sku) do update set payload=excluded.payload,version=fob_purchase_product.version+1,updated_at=excluded.updated_at").param("p",source.toString()).update();
    }
    JsonNode save(String role,String key,ObjectNode body) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/quotations").session(sessions.get(role)).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    JsonNode read(String role,String id) throws Exception {return mapper.readTree(mvc.perform(get("/api/v1/quotations/"+id).session(sessions.get(role))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");}
    @Test void savesSnapshotWithIdempotencyOwnerIsolationAndAdminInspectionRoundTrip() throws Exception {
        var key=UUID.randomUUID().toString();var row=save("employee",key,input);String id=row.path("id").asText();var snapshot=row.path("fob");
        assertEquals("fob",row.path("quoteMode").asText());assertEquals(id,save("employee",key,input).path("id").asText());
        for(String role:List.of("employee","super_admin","finance"))assertEquals(snapshot,read(role,id).path("fob"));
        mvc.perform(get("/api/v1/quotations/"+id).session(sessions.get("employee2"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/quotations").session(sessions.get("purchase")).with(csrf()).header("Idempotency-Key","denied").contentType("application/json").content(input.toString())).andExpect(status().isForbidden());
        var mark=mapper.createObjectNode().put("_version",row.path("_version").asLong()).put("_reviewVersion",0).put("spotChecked",true);
        for(String role:List.of("employee","finance"))mvc.perform(patch("/api/v1/quotations/"+id+"/spot-check").session(sessions.get(role)).with(csrf()).contentType("application/json").content(mark.toString())).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/quotations/"+id+"/spot-check").session(sessions.get("super_admin")).with(csrf()).contentType("application/json").content(mark.toString())).andExpect(status().isOk());
        var checked=read("employee",id);assertTrue(checked.path("spotChecked").asBoolean());assertEquals(snapshot,checked.path("fob"));
        mark.put("spotChecked",false).put("_reviewVersion",checked.path("_reviewVersion").asLong());
        mvc.perform(patch("/api/v1/quotations/"+id+"/spot-check").session(sessions.get("super_admin")).with(csrf()).contentType("application/json").content(mark.toString())).andExpect(status().isOk());
        assertFalse(read("employee",id).path("spotChecked").asBoolean());
        var search=mvc.perform(get("/api/v1/quotations/search").param("scope","mine").param("q",row.path("no").asText()).param("quoteMode","fob").session(sessions.get("employee"))).andExpect(status().isOk()).andReturn();
        assertEquals("fob",mapper.readTree(search.getResponse().getContentAsByteArray()).at("/data/items/0/quoteMode").asText());
        jdbc.sql("update finance_setting set payload='{"+'"'+"usdCny"+'"'+":7}',version=version+1 where setting_key='exchange-rate'").update();
        jdbc.sql("update fob_purchase_product set updated_at=now(),version=version+1 where sku='FOB-SAVE'").update();
        assertEquals(snapshot,read("employee",id).path("fob"));assertEquals(id,save("employee",key,input).path("id").asText());
        mvc.perform(post("/api/v1/quotations").session(sessions.get("employee")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(input.toString())).andExpect(status().isConflict());
    }
    @Test void staleSourceAndTamperedPricesNeverPersistOrLeakIntoOtherModes() throws Exception {
        long before=jdbc.sql("select count(*) from quotation_record").query(Long.class).single();
        var forged=input.deepCopy();((ArrayNode)forged.at("/fob/sheet/rows/0/prices")).set(0,DoubleNode.valueOf(.01));
        mvc.perform(post("/api/v1/quotations").session(sessions.get("employee")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(forged.toString())).andExpect(status().isConflict());
        var stale=input.deepCopy();((ObjectNode)stale.at("/fob/product")).put("updatedAt","2000-01-01T00:00:00Z");
        mvc.perform(post("/api/v1/quotations").session(sessions.get("employee")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(stale.toString())).andExpect(status().isConflict());
        forged.put("quoteMode","single");mvc.perform(post("/api/v1/quotations").session(sessions.get("employee")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(forged.toString())).andExpect(status().isUnprocessableEntity());
        assertEquals(before,jdbc.sql("select count(*) from quotation_record").query(Long.class).single());
    }
    @Test void concurrentRetriesProduceOneRecordAndOneAuditEntry() throws Exception {
        String key=UUID.randomUUID().toString();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            var start=new java.util.concurrent.CountDownLatch(1);
            var jobs=new ArrayList<java.util.concurrent.Future<String>>();
            for(int i=0;i<6;i++)jobs.add(executor.submit(()->{start.await();return save("employee",key,input.deepCopy()).path("id").asText();}));
            start.countDown();var ids=new HashSet<String>();for(var job:jobs)ids.add(job.get(30,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,ids.size());
            assertEquals(1L,jdbc.sql("select count(*) from audit_log where action='quotation.create' and resource_id=:id").param("id",ids.iterator().next()).query(Long.class).single());
        } finally {executor.shutdownNow();}
    }

}
