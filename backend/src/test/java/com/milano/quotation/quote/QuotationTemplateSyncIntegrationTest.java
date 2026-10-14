package com.milano.quotation.quote;

import com.milano.quotation.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest @ActiveProfiles("test")
class QuotationTemplateSyncIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired QuotationTemplateRepository templates;
    @Autowired UserAccountRepository users;
    @Autowired UserAccountService userService;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;QuotationPrincipal admin,employee;QuotationTemplateEntity source;
    QuotationPrincipal account(String account,String role) {
        if(users.findByAccountIgnoreCase(account).isEmpty()) users.saveAndFlush(UserAccount.create(account,account,"hash",role,false));
        return (QuotationPrincipal)userService.loadUserByUsername(account);
    }
    @BeforeEach void setup() {
        mvc=webAppContextSetup(context).apply(springSecurity()).build();
        admin=account("SYNCADMIN","super_admin");employee=account("SYNCSOURCE","employee");account("SYNCTARGET","employee");account("SYNCTARGET2","employee");
        for(String a:List.of("SYNCSOURCE","SYNCTARGET","SYNCTARGET2")) templates.deleteAll(templates.findByOwnerAccountOrderByUpdatedAtDesc(a));
        source=row("SYNCSOURCE","普货");
    }
    QuotationTemplateEntity row(String owner,String name) {
        var row=new QuotationTemplateEntity();row.id=UUID.randomUUID();row.ownerAccount=owner;row.name=name;
        var p=mapper.createObjectNode().put("name",name).put("ownerAccount",owner).put("ownerName",owner).put("ownerKey","ACCOUNT:"+owner).put("description","完整保留说明");
        var items=p.putArray("items");
        for(String region:List.of("1区","2区")) items.addObject().put("country","澳大利亚").put("quoteRegion",region).put("channelKey","same-channel").put("channelCode","SAME");
        row.payload=p;row.createdAt=Instant.now();row.updatedAt=row.createdAt;return templates.saveAndFlush(row);
    }
    ObjectNode request() {
        var b=mapper.createObjectNode().put("sourceAccount","SYNCSOURCE").put("prefix","魏-");
        b.putArray("templates").addObject().put("id",source.id.toString()).put("version",source.version);
        b.putArray("targetAccounts").add("SYNCTARGET").add("SYNCTARGET2");return b;
    }
    String sync(ObjectNode b,String key,int status) throws Exception {
        return mvc.perform(post("/api/v1/quotation-template-sync").with(user(admin)).with(csrf()).header("Idempotency-Key",key)
            .contentType("application/json").content(b.toString())).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    }
    @Test void copiesCompletePayloadPreservesExistingAndSkipsNamesAcrossRetries() throws Exception {
        var original=row("SYNCTARGET","原模板");var collision=row("SYNCTARGET2","魏-普货");var collisionPayload=collision.payload.deepCopy();
        var b=request();String key=UUID.randomUUID().toString();var response=mapper.readTree(sync(b,key,200)).path("data");
        assertEquals(1,response.path("created").asInt());assertEquals(1,response.path("skipped").asInt());
        var copied=templates.findByOwnerAccountOrderByUpdatedAtDesc("SYNCTARGET").stream().filter(t->t.name.equals("魏-普货")).findFirst().orElseThrow();
        assertNotEquals(source.id,copied.id);assertEquals(source.payload.path("items"),copied.payload.path("items"));assertEquals("完整保留说明",copied.payload.path("description").asText());
        assertEquals("ACCOUNT:SYNCTARGET",copied.payload.path("ownerKey").asText());assertEquals("SYNCTARGET",copied.payload.path("ownerName").asText());
        assertEquals(source.payload,templates.findById(source.id).orElseThrow().payload);assertEquals(original.payload,templates.findById(original.id).orElseThrow().payload);
        assertEquals(collisionPayload,templates.findById(collision.id).orElseThrow().payload);
        assertEquals(response,mapper.readTree(sync(b,key,200)).path("data"));
        var again=mapper.readTree(sync(b,UUID.randomUUID().toString(),200)).path("data");assertEquals(0,again.path("created").asInt());assertEquals(2,again.path("skipped").asInt());
        assertEquals(1,jdbc.queryForObject("select count(*) from audit_log where action='template.sync' and resource_id=?",Integer.class,copied.id.toString()));
    }
    @Test void rejectsStaleForeignInvalidTargetsAndLongNamesAtomically() throws Exception {
        var b=request();((ObjectNode)b.path("templates").get(0)).put("version",source.version+1);sync(b,UUID.randomUUID().toString(),409);
        b=request();b.put("sourceAccount","SYNCADMIN");sync(b,UUID.randomUUID().toString(),409);
        b=request();b.putArray("targetAccounts").add("SYNCSOURCE");sync(b,UUID.randomUUID().toString(),422);
        b=request();b.putArray("targetAccounts").add("SYNCTARGET").add("MISSINGUSER");sync(b,UUID.randomUUID().toString(),409);
        b=request();b.put("prefix","x".repeat(41));sync(b,UUID.randomUUID().toString(),422);
        assertTrue(templates.findByOwnerAccountOrderByUpdatedAtDesc("SYNCTARGET").isEmpty());
    }
    @Test void rejectsNonAdminReadsAndWritesAndMissingCsrf() throws Exception {
        var finance=account("SYNCFINANCE","finance");
        mvc.perform(get("/api/v1/quotation-template-sync/accounts").with(user(finance))).andExpect(status().isForbidden());
        for(String path:List.of("/accounts","/templates?account=SYNCSOURCE"))
            mvc.perform(get("/api/v1/quotation-template-sync"+path).with(user(employee))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/quotation-template-sync").with(user(employee)).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(request().toString())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/quotation-template-sync").with(user(admin)).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(request().toString())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/quotation-template-sync/accounts").with(user(admin))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));
    }
    @Test void disabledRecipientRejectsWholeBatchWithoutChangingOtherRecipients() throws Exception {
        var target=users.findByAccountIgnoreCase("SYNCTARGET2").orElseThrow();
        target.status="disabled";users.saveAndFlush(target);
        try {
            sync(request(),UUID.randomUUID().toString(),409);
            assertTrue(templates.findByOwnerAccountOrderByUpdatedAtDesc("SYNCTARGET").isEmpty());
            assertEquals(source.payload,templates.findById(source.id).orElseThrow().payload);
        } finally { target=users.findByAccountIgnoreCase("SYNCTARGET2").orElseThrow();target.status="enabled";users.saveAndFlush(target); }
    }
    @Test void concurrentAdministratorsDoNotCreateDuplicateCopies() throws Exception {
        var other=account("SYNCADMIN2","super_admin");var body=request().toString();
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var jobs=new ArrayList<java.util.concurrent.Future<String>>();
            for(var actor:List.of(admin,other)) jobs.add(pool.submit(()->{
                start.await();return mvc.perform(post("/api/v1/quotation-template-sync").with(user(actor)).with(csrf())
                    .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            }));
            start.countDown();int created=0;for(var job:jobs) created+=mapper.readTree(job.get(20,java.util.concurrent.TimeUnit.SECONDS)).path("data").path("created").asInt();
            assertEquals(2,created);
        }
        assertEquals(1,templates.findByOwnerAccountOrderByUpdatedAtDesc("SYNCTARGET").size());
        assertEquals(1,templates.findByOwnerAccountOrderByUpdatedAtDesc("SYNCTARGET2").size());
    }
    @Test void immediateRemovalPreservesOtherRegionAndRejectsLastChannelAndStaleVersion() throws Exception {
        var before=source.payload.deepCopy();var patch=mapper.createObjectNode().put("_version",source.version);
        patch.putArray("items").add(before.path("items").get(1));
        patch.putObject("_confirmedUpdate").put("templateId",source.id.toString()).put("baseVersion",source.version).put("source","template-channel-confirmation");
        mvc.perform(put("/api/v1/quotation-templates/"+source.id).with(user(employee)).with(csrf()).contentType("application/json").content(patch.toString())).andExpect(status().isOk());
        var saved=templates.findById(source.id).orElseThrow();assertEquals(1,saved.payload.path("items").size());assertEquals("2区",saved.payload.path("items").get(0).path("quoteRegion").asText());
        mvc.perform(put("/api/v1/quotation-templates/"+source.id).with(user(employee)).with(csrf()).contentType("application/json").content(patch.toString())).andExpect(status().isConflict());
        patch.put("_version",saved.version);((ObjectNode)patch.path("_confirmedUpdate")).put("baseVersion",saved.version);patch.putArray("items");
        mvc.perform(put("/api/v1/quotation-templates/"+source.id).with(user(employee)).with(csrf()).contentType("application/json").content(patch.toString())).andExpect(status().isUnprocessableEntity());
        assertEquals(saved.payload,templates.findById(source.id).orElseThrow().payload);
    }
}
