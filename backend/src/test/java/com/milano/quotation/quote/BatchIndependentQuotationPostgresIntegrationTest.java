package com.milano.quotation.quote;

import com.milano.quotation.security.*;
import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Real PostgreSQL persistence, permissions and transactions; pricing guard delegation is tested separately. */
@SpringBootTest @ActiveProfiles("test") @Testcontainers(disabledWithoutDocker=true)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class BatchIndependentQuotationPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.sql.init.mode",()->"never");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");r.add("spring.jpa.defer-datasource-initialization",()->"false");r.add("spring.flyway.enabled",()->"true");
    }
    @Autowired WebApplicationContext context; @Autowired UserAccountRepository users; @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper; @Autowired JdbcClient jdbc;
    @MockitoBean QuotationSubmissionValidator validator;
    @MockitoBean QuotationReadinessService readiness;
    @MockitoBean com.milano.quotation.logistics.LogisticsQuotationGuard logistics;
    MockMvc mvc; Map<String,MockHttpSession> sessions;
    @BeforeEach void setup() throws Exception {
        mvc=webAppContextSetup(context).apply(springSecurity()).build(); sessions=new HashMap<>();
        for(String role:List.of("super_admin","finance","employee","employee2","purchase")) {
            String account=role.toUpperCase()+UUID.randomUUID().toString().substring(0,8);
            users.saveAndFlush(UserAccount.create(account,role,encoder.encode("BatchTest123!"),role.equals("employee2")?"employee":role,false));
            var response=mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json").content(mapper.createObjectNode().put("account",account).put("password","BatchTest123!").toString())).andExpect(status().isOk()).andReturn();
            sessions.put(role,(MockHttpSession)response.getRequest().getSession(false));
        }
    }

    ObjectNode ordinary(int size) { var body=mapper.createObjectNode().put("kind","single");body.set("document",BatchSingleQuotationTest.input(size));return body; }
    org.springframework.test.web.servlet.ResultActions submit(String role,String key,ObjectNode body) throws Exception {
        return mvc.perform(post("/api/v1/quotations/batch").session(sessions.get(role)).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(body.toString()));
    }
    JsonNode save(String key,ObjectNode body) throws Exception { return mapper.readTree(submit("employee",key,body).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data"); }
    JsonNode read(String id) throws Exception { return mapper.readTree(mvc.perform(get("/api/v1/quotations/"+id).session(sessions.get("employee"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data"); }
    JsonNode review(JsonNode record,String action,String result) throws Exception {
        var body=mapper.createObjectNode().put("action",action).put("_version",record.path("_version").asLong()).put("_reviewVersion",record.path("_reviewVersion").asLong()).put("financeReviewStatus",result).put("note","逐条核实");
        return mapper.readTree(mvc.perform(patch("/api/v1/quotations/"+record.path("id").asText()+"/finance-review").session(sessions.get("finance")).with(csrf()).contentType("application/json").content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    long count(String table) { return jdbc.sql("select count(*) from "+table).query(Long.class).single(); }
    @Test void fourteenSingleRecordsUseOriginalReviewAndBatchSearchWithoutTouchingLegacy() throws Exception {
        long before=count("quotation_record"), legacy=count("batch_single_quotation");
        var body=ordinary(14);var key=UUID.randomUUID().toString();var result=save(key,body);
        assertEquals(14,result.path("records").size());assertEquals(before+14,count("quotation_record"));assertEquals(legacy,count("batch_single_quotation"));
        var ids=new HashSet<String>();var nos=new HashSet<String>();
        for(var item:result.path("records")) {ids.add(item.path("id").asText());nos.add(item.path("no").asText());var row=read(item.path("id").asText());assertEquals("single",row.path("quoteMode").asText());assertEquals("pending",row.path("financeReviewStatus").asText());assertEquals(result.path("batchNo"),row.at("/quotationBatch/no"));assertEquals(14,row.at("/quotationBatch/count").asInt());}
        assertEquals(14,ids.size());assertEquals(14,nos.size());
        var first=read(result.at("/records/0/id").asText());var second=read(result.at("/records/1/id").asText());
        var approved=review(review(first,"claim","pending"),"complete","approved");assertEquals("approved",approved.path("financeReviewStatus").asText());assertEquals(second,read(second.path("id").asText()));
        assertEquals(first.path("customerQuote"),approved.path("customerQuote"));
        mvc.perform(get("/api/v1/quotations/search").session(sessions.get("finance")).param("scope","company").param("product",result.path("batchNo").asText())).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(14));
        mvc.perform(get("/api/v1/quotations/search").session(sessions.get("employee2")).param("scope","company").param("product",result.path("batchNo").asText())).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        doThrow(AppException.conflict("资料已更新")).when(readiness).assertCanCreate(any());assertEquals(result,save(key,body));assertEquals(before+14,count("quotation_record"));
        assertEquals(approved,read(first.path("id").asText()));
    }
    @Test void laterPersistenceFailureRollsBackEarlierRecordAndRetryCreatesExactlyTwo() throws Exception {
        long before=count("quotation_record");var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call->{if(calls.incrementAndGet()==4)throw AppException.conflict("SKU1渠道已变化");return null;}).when(logistics).validate(any());
        var body=ordinary(2);var key=UUID.randomUUID().toString();
        submit("employee",key,body).andExpect(status().isConflict());assertEquals(before,count("quotation_record"));
        reset(logistics);assertEquals(2,save(key,body).path("records").size());assertEquals(before+2,count("quotation_record"));
    }
    @Test void invalidPermissionsDuplicatesAndIdempotencyKeyReuseCannotCreateRecords() throws Exception {
        long before=count("quotation_record");var body=ordinary(2);
        submit("purchase",UUID.randomUUID().toString(),body).andExpect(status().isForbidden());
        ((ObjectNode)body.at("/document/items/1/input")).put("primarySku","SKU0");
        submit("employee",UUID.randomUUID().toString(),body).andExpect(status().isUnprocessableEntity());assertEquals(before,count("quotation_record"));
        var key=UUID.randomUUID().toString();save(key,ordinary(1));submit("employee",key,ordinary(2)).andExpect(status().isConflict());assertEquals(before+1,count("quotation_record"));
    }
    @Test void concurrentRetryCreatesOnlyOneSetOfRecords() throws Exception {
        long before=count("quotation_record");var body=ordinary(2);var key=UUID.randomUUID().toString();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->save(key,body));var b=executor.submit(()->save(key,body));
            assertEquals(a.get(30,java.util.concurrent.TimeUnit.SECONDS),b.get(30,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(before+2,count("quotation_record"));
    }
    void seedFob(ObjectNode body) {
        jdbc.sql("insert into finance_setting(setting_key,payload,version,updated_at) values('exchange-rate','{\"usdCny\":6.7}'::jsonb,0,now()) on conflict(setting_key) do update set payload=excluded.payload").update();
        for(var item:body.at("/fob/items")) {
            var source=(ObjectNode)item.path("product").deepCopy();source.put("weightRaw","60 g");
            jdbc.sql("insert into fob_purchase_product(sku,payload,version,created_at,updated_at) values(:sku,cast(:p as jsonb),0,now(),'2026-10-09T00:00:00Z') on conflict(sku) do update set payload=excluded.payload,updated_at=excluded.updated_at")
                .param("sku",source.path("sku").asText()).param("p",source.toString()).update();
        }
    }
    @Test void fobFourteenRecordsAreSchemaOneAndUseOrdinaryFinanceReview() throws Exception {
        var body=BatchQuotationSubmissionTest.request(FobBatchQuotationTest.input(14));seedFob(body);var key=UUID.randomUUID().toString();
        var result=save(key,body);assertEquals(14,result.path("records").size());
        for(var item:result.path("records")) {var row=read(item.path("id").asText());assertEquals(1,row.at("/fob/schemaVersion").asInt());assertFalse(row.has("fobItemReviews"));assertTrue(row.at("/fob/sourceSnapshot").isObject());}
        var first=read(result.at("/records/0/id").asText());var second=read(result.at("/records/1/id").asText());
        var rejected=review(review(first,"claim","pending"),"complete","rejected");assertEquals("rejected",rejected.path("financeReviewStatus").asText());assertEquals(second,read(second.path("id").asText()));
        jdbc.sql("update finance_setting set payload='{\"usdCny\":7}'::jsonb where setting_key='exchange-rate'").update();
        assertEquals(result,save(key,body));assertEquals(first.path("fob"),read(first.path("id").asText()).path("fob"));
    }
    @Test void repeatedFobQuantitiesSaveOneIndependentRecordAndTamperingSavesNothing() throws Exception {
        var body=BatchQuotationSubmissionTest.request(BatchQuotationSubmissionTest.repeatedQuantities());seedFob(body);
        var result=save(UUID.randomUUID().toString(),body);assertEquals(1,result.path("records").size());
        var row=read(result.at("/records/0/id").asText());assertEquals("[2,26]",row.at("/fob/quantities").toString());assertEquals(2,row.at("/fob/sheet/rows").size());
        long before=count("quotation_record");((ArrayNode)body.at("/fob/sheet/rows/1/prices")).set(0,DoubleNode.valueOf(.01));
        submit("employee",UUID.randomUUID().toString(),body).andExpect(status().isConflict());assertEquals(before,count("quotation_record"));
    }
}
