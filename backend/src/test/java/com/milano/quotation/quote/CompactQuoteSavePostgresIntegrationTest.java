package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Replays synthetic payloads captured by compact-save-browser.mjs.
 * Source readiness/logistics lookup are fixtures; validation, controllers, persistence,
 * transactions, idempotency and read-back use the actual application and PostgreSQL. */
@SpringBootTest @ActiveProfiles("test") @Testcontainers(disabledWithoutDocker=true)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CompactQuoteSavePostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.sql.init.mode",()->"never");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");r.add("spring.jpa.defer-datasource-initialization",()->"false");r.add("spring.flyway.enabled",()->"true");
    }
    @MockitoBean QuotationReadinessService readiness;
    @MockitoBean com.milano.quotation.logistics.LogisticsQuotationGuard logisticsGuard;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired QuotationRecordRepository records;
    @Autowired com.milano.quotation.security.UserAccountRepository users;
    @Autowired com.milano.quotation.security.UserAccountService userService;

    @Test void browserCopyFailureRetryReorderAndDragDoNotAlterPricesOrPreventSaving() throws Exception {
        var mvc=webAppContextSetup(context).apply(springSecurity()).build();
        users.saveAndFlush(com.milano.quotation.security.UserAccount.create("COMPACTQA","本地测试","hash","super_admin",false));
        var auth=user(userService.loadUserByUsername("COMPACTQA"));
        long count=records.count();
        JsonNode baseline=null;
        for(var name:java.util.List.of("baseline","features")) {
            var body=mapper.readTree(getClass().getResourceAsStream("/compact-quote-save/"+name+".json"));
            var result=mvc.perform(post("/api/v1/quotations").with(auth).with(csrf()).header("Idempotency-Key","compact-"+name).contentType("application/json").content(body.toString()))
                .andExpect(status().isOk()).andReturn();
            var saved=mapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
            var id=saved.path("id").asText();
            assertFalse(id.isBlank());
            var read=mapper.readTree(mvc.perform(get("/api/v1/quotations/"+id).with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
            for(var key:java.util.List.of("systemQuoteUsd","systemQuoteCny","quoteOptions","systemQuantityQuotes","customerQuote","weightSnapshot","exchangeRate")) assertEquals(body.path(key),read.path(key),name+":"+key);
            assertEquals(read.path("customerQuote"),records.findById(java.util.UUID.fromString(id)).orElseThrow().payload.path("customerQuote"));
            mvc.perform(post("/api/v1/quotations").with(auth).with(csrf()).header("Idempotency-Key","compact-"+name).contentType("application/json").content(body.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
            if(baseline==null) baseline=read;
            else {
                for(var key:java.util.List.of("systemQuoteUsd","systemQuoteCny","weightSnapshot")) assertEquals(baseline.path(key),read.path(key));
                assertEquals(byId(baseline.path("quoteOptions"),"id"),byId(read.path("quoteOptions"),"id"));
                for(var key:java.util.List.of("customerQuote","systemQuantityQuotes")) {
                    assertEquals(baseline.path(key).path("quantities"),read.path(key).path("quantities"));
                    assertEquals(byId(baseline.path(key).path("rows"),"optionId"),byId(read.path(key).path("rows"),"optionId"));
                }
                assertNotEquals(baseline.at("/customerQuote/rowOrder"),read.at("/customerQuote/rowOrder"));
                var previous=mapper.readTree(mvc.perform(get("/api/v1/quotations/"+baseline.path("id").asText()).with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
                assertEquals(baseline,previous,"Saving a new quote must not rewrite the earlier snapshot");
            }
        }
        assertEquals(count+2,records.count(),"Retries must not create duplicate records");
    }
    private java.util.Map<String,JsonNode> byId(JsonNode rows,String field) {
        var map=new java.util.TreeMap<String,JsonNode>();
        rows.forEach(row->assertNull(map.put(row.path(field).asText(),row),"Duplicate row identity"));
        return map;
    }
}
