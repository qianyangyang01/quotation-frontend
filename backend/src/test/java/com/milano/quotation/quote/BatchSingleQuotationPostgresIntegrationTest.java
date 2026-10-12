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
class BatchSingleQuotationPostgresIntegrationTest {
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
    JsonNode save(String key,ObjectNode body) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/batch-single-quotations").session(sessions.get("employee")).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    @Test void editedPresentationAndAverageSnapshotSurviveDatabaseReadback() throws Exception {
        var input=BatchSingleQuotationTest.averagedInput();
        for(var node:List.of((ObjectNode)input.path("sheet"),(ObjectNode)input.at("/items/0/sheet"))) {
            node.put("title","Edited quote").put("whatsapp","+86 10000000000").put("sizeRules","Size S / M");
            node.putArray("hiddenColumns").add("processingTime");
        }
        var saved=save(UUID.randomUUID().toString(),input);
        var read=mapper.readTree(mvc.perform(get("/api/v1/batch-single-quotations/"+saved.path("id").asText()).session(sessions.get("employee"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
        assertEquals(saved,read);assertEquals("Edited quote",read.at("/sheet/title").asText());
        assertEquals("plan",read.at("/items/0/input/customerQuote/averagePlans/0/id").asText());
    }
    @Test void oneDocumentRoundTripOwnerIsolationIdempotencyAndNoOrdinaryWrites() throws Exception {
        long before=jdbc.sql("select count(*) from quotation_record").query(Long.class).single();
        var input=BatchSingleQuotationTest.input(3);var key=UUID.randomUUID().toString();var result=save(key,input);var id=result.path("id").asText();
        assertEquals(3,result.path("items").size());assertEquals(3,result.at("/sheet/rows").size());assertEquals(id,save(key,input).path("id").asText());
        for(var role:List.of("employee","super_admin")) {
            var read=mapper.readTree(mvc.perform(get("/api/v1/batch-single-quotations/"+id).session(sessions.get(role))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
            assertEquals(result,read);
        }
        mvc.perform(get("/api/v1/batch-single-quotations/"+id).session(sessions.get("employee2"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/batch-single-quotations").session(sessions.get("purchase")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(input.toString())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/batch-single-quotations").session(sessions.get("employee2")).param("scope","company")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/batch-single-quotations").session(sessions.get("employee"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].skuCount").value(3));
        doThrow(AppException.conflict("资料已变化")).when(readiness).assertCanCreate(any());
        assertEquals(id,save(key,input).path("id").asText()); // Retry retains original snapshot even after prices change.
        assertEquals(before,jdbc.sql("select count(*) from quotation_record").query(Long.class).single());
    }
    @Test void staleSecondSkuRollsBackEntireBatchAndKeepsOrdinaryRecords() throws Exception {
        long batches=jdbc.sql("select count(*) from batch_single_quotation").query(Long.class).single();
        long ordinary=jdbc.sql("select count(*) from quotation_record").query(Long.class).single();
        doAnswer(call->{var in=(ObjectNode)call.getArgument(0);if("SKU1".equals(in.path("primarySku").asText()))throw AppException.conflict("SKU1资料已更新");return null;}).when(readiness).assertCanCreate(any());
        mvc.perform(post("/api/v1/batch-single-quotations").session(sessions.get("employee")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(BatchSingleQuotationTest.input(3).toString())).andExpect(status().isConflict());
        assertEquals(batches,jdbc.sql("select count(*) from batch_single_quotation").query(Long.class).single());assertEquals(ordinary,jdbc.sql("select count(*) from quotation_record").query(Long.class).single());
    }

    JsonNode readBatch(JsonNode row) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/batch-single-quotations/"+row.path("id").asText()).session(sessions.get("employee"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    org.springframework.test.web.servlet.ResultActions mutate(String role,JsonNode row,String path,JsonNode body,String key) throws Exception {
        return mvc.perform(post("/api/v1/batch-single-quotations/"+row.path("id").asText()+"/"+path).session(sessions.get(role)).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(body.toString()));
    }
    ObjectNode action(JsonNode row,String action) {return mapper.createObjectNode().put("_reviewVersion",row.path("_reviewVersion").asLong()).put("action",action);}
    JsonNode change(JsonNode row,ObjectNode body) throws Exception {
        return mapper.readTree(mutate("super_admin",row,"review",body,UUID.randomUUID().toString()).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    @Test void selectedReviewCorrectionAndRetryPreserveOriginalAndOtherItems() throws Exception {
        var row=save(UUID.randomUUID().toString(),BatchSingleQuotationTest.input(3));var id=row.path("id").asText();
        String original=jdbc.sql("select payload::text from batch_single_quotation where id=cast(:id as uuid)").param("id",id).query(String.class).single();
        mutate("employee",row,"review",action(row,"claim"),"denied").andExpect(status().isForbidden());
        var claimed=change(row,action(row,"claim"));
        mutate("finance",row,"review",action(claimed,"claim"),"other-claim").andExpect(status().isConflict());
        var approve=action(claimed,"complete").put("result","channel-exempt").put("note","渠道已核实");approve.putArray("items").addObject().put("index",2);
        String key=UUID.randomUUID().toString();
        mutate("super_admin",row,"review",approve,key).andExpect(status().isOk());var approved=readBatch(row);
        mutate("super_admin",row,"review",approve,key).andExpect(status().isOk()).andExpect(jsonPath("$.data._reviewVersion").value(approved.path("_reviewVersion").asInt()));
        assertEquals("pending",approved.at("/review/items/0/status").asText());assertEquals("channel-exempt",approved.at("/review/items/2/reviewResult").asText());
        claimed=change(approved,action(approved,"claim"));var reject=action(claimed,"complete").put("result","rejected");reject.putArray("items").addObject().put("index",0).put("reason","");
        mutate("super_admin",row,"review",reject,"empty-reason").andExpect(status().isUnprocessableEntity());
        ((ObjectNode)reject.at("/items/0")).put("reason","采购价格需核实");var returned=change(row,reject);
        assertEquals("pending",returned.at("/review/items/1/status").asText());assertEquals(approved.at("/review/items/2"),returned.at("/review/items/2"));
        claimed=change(returned,action(returned,"claim"));var illegal=action(claimed,"complete").put("result","approved");illegal.putArray("items").addObject().put("index",0);
        mutate("super_admin",row,"review",illegal,"uncorrected").andExpect(status().isConflict());
        returned=change(row,action(claimed,"cancel"));
        var correction=mapper.createObjectNode().put("_reviewVersion",returned.path("_reviewVersion").asLong());correction.putArray("items").addObject().put("index",0);
        var fresh=BatchSingleQuotationTest.input(1);((ObjectNode)fresh.at("/items/0/input")).put("purchaseUnitPriceCny",11);correction.set("document",fresh);
        mutate("employee2",row,"corrections",correction,"wrong-owner").andExpect(status().isNotFound());
        key=UUID.randomUUID().toString();mutate("employee",row,"corrections",correction,key).andExpect(status().isOk());var revised=readBatch(row);
        mutate("employee",row,"corrections",correction,key).andExpect(status().isOk()).andExpect(jsonPath("$.data.review.revision").value(1));
        assertEquals("pending",revised.at("/review/items/0/status").asText());assertEquals(returned.at("/review/items/2"),revised.at("/review/items/2"));
        assertEquals(returned.at("/items/1"),revised.at("/items/1"));assertEquals(returned.at("/items/2"),revised.at("/items/2"));
        assertTrue(revised.at("/review/history").toString().contains("采购价格需核实"));
        assertEquals(original,jdbc.sql("select payload::text from batch_single_quotation where id=cast(:id as uuid)").param("id",id).query(String.class).single());
        var originalView=mapper.readTree(mvc.perform(get("/api/v1/batch-single-quotations/"+id+"/original").session(sessions.get("employee"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");assertEquals(mapper.readTree(original),originalView);
        mutate("employee",row,"corrections",correction,"stale-correction").andExpect(status().isConflict());
        claimed=change(revised,action(revised,"claim"));approve=action(claimed,"complete").put("result","logistics-exempt");approve.putArray("items").addObject().put("index",0);
        var finished=change(row,approve);assertEquals("logistics-exempt",finished.at("/review/items/0/reviewResult").asText());assertEquals("pending",finished.at("/review/items/1/status").asText());
    }
}
