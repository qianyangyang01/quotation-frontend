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
        for(String role:List.of("super_admin","employee","employee2","purchase")) {
            String account=role.toUpperCase()+UUID.randomUUID().toString().substring(0,8);
            users.saveAndFlush(UserAccount.create(account,role,encoder.encode("BatchTest123!"),role.equals("employee2")?"employee":role,false));
            var response=mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json").content(mapper.createObjectNode().put("account",account).put("password","BatchTest123!").toString())).andExpect(status().isOk()).andReturn();
            sessions.put(role,(MockHttpSession)response.getRequest().getSession(false));
        }
    }
    JsonNode save(String key,ObjectNode body) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/batch-single-quotations").session(sessions.get("employee")).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
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
}
