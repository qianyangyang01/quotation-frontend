package com.milano.quotation.quote;

import com.milano.quotation.security.*;
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
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Uses actual database users, password authentication and independent sessions, not mocked authorities. */
@SpringBootTest @ActiveProfiles("test") @Testcontainers(disabledWithoutDocker=true)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class QuotationRoleConsistencyPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.sql.init.mode",()->"never");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("spring.jpa.defer-datasource-initialization",()->"false");r.add("spring.flyway.enabled",()->"true");
    }
    @Autowired WebApplicationContext context;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired QuotationRecordRepository records;
    MockMvc mvc; Map<String,MockHttpSession> sessions; Map<String,UserAccount> accounts;
    @BeforeEach void setup() throws Exception {
        mvc=webAppContextSetup(context).apply(springSecurity()).build();sessions=new LinkedHashMap<>();accounts=new LinkedHashMap<>();
        String password="RoleAcceptance123",hash=encoder.encode(password),suffix=UUID.randomUUID().toString().substring(0,8).toUpperCase();
        for(String role:List.of("super_admin","finance","employee","employee2","purchase","logistics")) {
            var account=users.saveAndFlush(UserAccount.create(role.toUpperCase()+suffix,role,hash,role.equals("employee2")?"employee":role,false));accounts.put(role,account);
            var result=mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json").content(mapper.createObjectNode().put("account",account.account).put("password",password).toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value(account.roleKey)).andReturn();
            sessions.put(role,(MockHttpSession)result.getRequest().getSession(false));
        }
    }
    JsonNode read(String role,String path) throws Exception {
        return mapper.readTree(mvc.perform(get(path).session(sessions.get(role))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    void writeFob(String sku,String price) throws Exception {
        writeFob("super_admin",sku,price);
    }
    void writeFob(String role,String sku,String price) throws Exception {
        var rows=mapper.createArrayNode().add(mapper.createObjectNode().put("sku",sku).put("weightRaw","80").put("moqRaw","1").put("priceRaw",price).put("freightRaw","100件18.5;200件40"));
        var preview=mapper.readTree(mvc.perform(post("/api/v1/fob-purchase-products/paste/preview").session(sessions.get(role)).with(csrf()).contentType("application/json").content(rows.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        var body=mapper.createObjectNode();body.set("rows",rows);body.putArray("expected").add(preview.path("rows").get(0).path("expected"));body.put("digest",preview.path("digest").asText());
        mvc.perform(post("/api/v1/fob-purchase-products/paste/confirm").session(sessions.get(role)).with(csrf()).contentType("application/json").content(body.toString())).andExpect(status().isOk());
    }
    @Test void actualQuoteRoleSessionsReadIdenticalSourcesAndFreshUpdatesWithoutGainingMaintenance() throws Exception {
        String sku="ROLE-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(),path="/api/v1/fob-purchase-products/"+sku;
        writeFob(sku,"单价8.54;200-209件7.13;210件以上6.64");
        var before=read("super_admin",path);assertEquals(3,before.path("parsed").path("priceTiers").size());
        var finance=read("super_admin","/api/v1/finance-settings");assertTrue(finance.has("exchange-rate"));
        for(String role:List.of("employee","employee2","finance")) {
            assertEquals(before,read(role,path),role+" FOB source must be identical");
            assertEquals(finance,read(role,"/api/v1/finance-settings"),role+" must share the same rate and finance versions");
        }
        assertEquals(before,read("purchase",path),"Procurement must read the same FOB source");
        for(String role:List.of("employee","employee2","finance","logistics")) {
            mvc.perform(get(path+"/history").session(sessions.get(role))).andExpect(status().isForbidden());
            for(String action:List.of("preview","confirm"))
                mvc.perform(post("/api/v1/fob-purchase-products/paste/"+action).session(sessions.get(role)).with(csrf()).contentType("application/json").content(action.equals("preview")?"[]":"{}" )).andExpect(status().isForbidden());
        }
        mvc.perform(get(path).session(sessions.get("logistics"))).andExpect(status().isForbidden());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        writeFob(sku,"单价9;200-209件8;210件以上7");
        var updated=read("super_admin",path);assertTrue(updated.path("version").asLong()>before.path("version").asLong());
        for(String role:List.of("employee","employee2","finance","purchase")) assertEquals(updated,read(role,path),role+" must read the newly saved version");
        String ordinary="STANDARD-"+UUID.randomUUID().toString().substring(0,8).toUpperCase();
        var payload=mapper.createObjectNode().put("sku",ordinary).put("dataSource","standard").put("weightG",80).put("minOrderQty",1).put("purchasePriceCny",8.54).put("freight100Cny",18.5);
        payload.putArray("priceTiers").addObject().put("minQty",1).putNull("maxQty").put("unitPriceCny",8.54);
        jdbc.sql("insert into purchase_product(id,sku,payload,catalog_state,quote_ready,version,created_at,updated_at) values(:id,:sku,cast(:payload as jsonb),'ready',true,0,now(),now())").param("id",UUID.randomUUID()).param("sku",ordinary).param("payload",payload.toString()).update();
        var standard=read("super_admin","/api/v1/purchase-products/"+ordinary);
        for(String role:List.of("employee","employee2","finance","purchase")) {
            mvc.perform(get("/api/v1/fob-purchase-products/"+ordinary).session(sessions.get(role))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FOB_PURCHASE_NOT_FOUND"));
            assertEquals(standard,read(role,"/api/v1/purchase-products/"+ordinary),"Standard fallback must use the same purchase data");
        }
    }
    @Test void procurementMaintainsSharedFobDataWithAuditCsrfAndConcurrentUpdateProtection() throws Exception {
        String sku="PURCHASE-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(),path="/api/v1/fob-purchase-products/"+sku;
        writeFob("purchase",sku,"10");
        var initial=read("purchase",path);
        assertEquals(initial,read("super_admin",path));
        var history=read("purchase",path+"/history");
        assertEquals(accounts.get("purchase").account,history.get(0).path("actorAccount").asText());
        assertEquals(history,read("super_admin",path+"/history"));
        var rows=mapper.createArrayNode().add(mapper.createObjectNode().put("sku",sku).put("priceRaw","12"));
        mvc.perform(post("/api/v1/fob-purchase-products/paste/preview").session(sessions.get("purchase")).contentType("application/json").content(rows.toString())).andExpect(status().isForbidden());
        var preview=mapper.readTree(mvc.perform(post("/api/v1/fob-purchase-products/paste/preview").session(sessions.get("purchase")).with(csrf()).contentType("application/json").content(rows.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        var body=mapper.createObjectNode();body.set("rows",rows);body.putArray("expected").add(preview.path("rows").get(0).path("expected"));body.put("digest",preview.path("digest").asText());
        mvc.perform(post("/api/v1/fob-purchase-products/paste/confirm").session(sessions.get("purchase")).contentType("application/json").content(body.toString())).andExpect(status().isForbidden());
        writeFob(sku,"11");
        mvc.perform(post("/api/v1/fob-purchase-products/paste/confirm").session(sessions.get("purchase")).with(csrf()).contentType("application/json").content(body.toString())).andExpect(status().isConflict());
        assertEquals(11,read("purchase",path).path("parsed").path("priceTiers").get(0).path("unitPriceCny").asInt());
        assertEquals(2,read("purchase",path+"/history").size());
        writeFob("purchase",sku,"12");
        var updated=read("purchase",path);
        for(String role:List.of("super_admin","employee","employee2","finance")) assertEquals(updated,read(role,path),role+" must see procurement's update");
        assertEquals(accounts.get("purchase").account,read("super_admin",path+"/history").get(0).path("actorAccount").asText());
        var purchase=users.findById(accounts.get("purchase").id).orElseThrow();purchase.roleKey="logistics";users.saveAndFlush(purchase);
        mvc.perform(get(path+"/history").session(sessions.get("purchase"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/fob-purchase-products/paste/preview").session(sessions.get("purchase")).with(csrf()).contentType("application/json").content(rows.toString())).andExpect(status().isForbidden());
    }
    @Test void actualSessionsShareInspectionMetadataButKeepOwnerScopeAndHistoryUnchanged() throws Exception {
        var r=new QuotationRecordEntity();r.id=UUID.randomUUID();r.quoteNo="ROLE-"+r.id.toString().substring(0,30);r.ownerAccount=accounts.get("employee").account;r.status="pending";
        r.createdAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);r.updatedAt=r.createdAt;
        r.payload=mapper.createObjectNode().put("id",r.id.toString()).put("no",r.quoteNo).put("systemQuoteUsd",6.35).put("exchangeRate",6.7);r=records.saveAndFlush(r);
        var original=records.findById(r.id).orElseThrow();String path="/api/v1/quotations/"+r.id;
        var before=read("super_admin",path);assertEquals(before,read("finance",path));assertEquals(before,read("employee",path));
        for(String role:List.of("employee","employee2","finance","purchase","logistics"))
            mvc.perform(patch(path+"/spot-check").session(sessions.get(role)).with(csrf()).contentType("application/json").content("{\"_version\":"+r.version+"}")).andExpect(status().isForbidden());
        mvc.perform(patch(path+"/spot-check").session(sessions.get("super_admin")).with(csrf()).contentType("application/json").content("{\"_version\":"+r.version+"}")).andExpect(status().isOk());
        var marked=read("super_admin",path);assertTrue(marked.path("spotChecked").asBoolean());
        for(String role:List.of("employee","finance")) {
            assertEquals(marked,read(role,path),"The saved quote and its inspection metadata must match");
            var polled=read(role,"/api/v1/quotations/review-status?ids="+r.id).get(0);
            for(String field:List.of("spotChecked","spotCheckedBy","spotCheckedAccount","spotCheckedAt","_reviewVersion")) assertEquals(marked.path(field),polled.path(field),field);
        }
        mvc.perform(get(path).session(sessions.get("employee2"))).andExpect(status().isForbidden());
        assertTrue(read("employee2","/api/v1/quotations/review-status?ids="+r.id).isEmpty());
        assertTrue(read("employee2","/api/v1/quotations/search?scope=company&spotCheck=checked").path("items").isEmpty());
        var cancel=mapper.createObjectNode().put("_version",r.version).put("_reviewVersion",marked.path("_reviewVersion").asLong()).put("spotChecked",false);
        for(String role:List.of("employee","finance","purchase","logistics"))
            mvc.perform(patch(path+"/spot-check").session(sessions.get(role)).with(csrf()).contentType("application/json").content(cancel.toString())).andExpect(status().isForbidden());
        mvc.perform(patch(path+"/spot-check").session(sessions.get("super_admin")).with(csrf()).contentType("application/json").content(cancel.toString())).andExpect(status().isOk());
        var cancelled=read("super_admin",path);assertFalse(cancelled.path("spotChecked").asBoolean());assertFalse(cancelled.has("spotCheckedAt"));
        for(String role:List.of("employee","finance")) {
            assertEquals(cancelled,read(role,path));assertFalse(read(role,"/api/v1/quotations/review-status?ids="+r.id).get(0).path("spotChecked").asBoolean());
        }
        var stored=records.findById(r.id).orElseThrow();assertEquals(original.payload,stored.payload);assertEquals(original.version,stored.version);assertEquals(original.updatedAt,stored.updatedAt);
    }
    @Test void existingSessionsRecheckRoleChangesDisabledAccountsAndCrossTabAccountIdentity() throws Exception {
        String sku="ROLE-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(),path="/api/v1/fob-purchase-products/"+sku;writeFob(sku,"10");
        read("employee",path);
        mvc.perform(get(path).session(sessions.get("employee")).header("X-Expected-Account",accounts.get("employee2").account)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_CHANGED"));
        var employee=users.findById(accounts.get("employee").id).orElseThrow();employee.roleKey="logistics";users.saveAndFlush(employee);
        mvc.perform(get(path).session(sessions.get("employee"))).andExpect(status().isForbidden());
        var disabled=users.findById(accounts.get("employee2").id).orElseThrow();disabled.status="disabled";users.saveAndFlush(disabled);
        mvc.perform(get(path).session(sessions.get("employee2"))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }
}
