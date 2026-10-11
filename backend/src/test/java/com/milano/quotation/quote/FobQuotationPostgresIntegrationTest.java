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
    @Test void batchPreviewSaveSearchOwnershipDealAndSnapshotRoundTrip() throws Exception {
        var batch=FobBatchQuotationTest.input(20);
        for(var item:batch.at("/fob/items")) {
            var p=(ObjectNode)item.path("product").deepCopy();p.put("weightRaw","60 g");
            jdbc.sql("insert into fob_purchase_product(sku,payload,version,created_at,updated_at) values(:sku,cast(:p as jsonb),0,now(),'2026-10-09T00:00:00Z') on conflict(sku) do update set payload=excluded.payload,updated_at=excluded.updated_at")
                .param("sku",p.path("sku").asText()).param("p",p.toString()).update();
        }
        long before=jdbc.sql("select count(*) from quotation_record").query(Long.class).single();
        mvc.perform(post("/api/v1/quotations/fob/preview").session(sessions.get("employee")).with(csrf()).contentType("application/json").content(batch.toString())).andExpect(status().isOk());
        assertEquals(before,jdbc.sql("select count(*) from quotation_record").query(Long.class).single());
        mvc.perform(post("/api/v1/quotations/fob/preview").session(sessions.get("purchase")).with(csrf()).contentType("application/json").content(batch.toString())).andExpect(status().isForbidden());
        var key=UUID.randomUUID().toString();var saved=save("employee",key,batch);var id=saved.path("id").asText();var snapshot=saved.path("fob");
        assertEquals(20,snapshot.path("items").size());assertEquals(id,save("employee",key,batch).path("id").asText());
        assertEquals(before+1,jdbc.sql("select count(*) from quotation_record").query(Long.class).single());
        assertEquals(snapshot,read("finance",id).path("fob"));
        mvc.perform(get("/api/v1/quotations/"+id).session(sessions.get("employee2"))).andExpect(status().isForbidden());
        var search=mvc.perform(get("/api/v1/quotations/search").param("q","SKU19").param("quoteMode","fob").session(sessions.get("employee"))).andExpect(status().isOk()).andReturn();
        assertEquals(id,mapper.readTree(search.getResponse().getContentAsByteArray()).at("/data/items/0/id").asText());
        var deal=mapper.createObjectNode().put("_version",saved.path("_version").asLong()).put("status","won").put("closedAt","2026-10-11").put("actualQuoteUsd",3.04).put("actualQuoteCny",20.368).put("dealQuantity",2);
        deal.putArray("dealLines").addObject().put("id","batch-deal").put("optionId","fob:19:declared").put("unitPriceUsd",1.52).put("quantity",2).put("amountUsd",3.04);
        mvc.perform(patch("/api/v1/quotations/"+id).session(sessions.get("employee")).with(csrf()).contentType("application/json").content(deal.toString())).andExpect(status().isOk());
        var updated=read("employee",id);assertEquals(snapshot,updated.path("fob"));assertEquals("SKU19 · 报关",updated.at("/dealLines/0/optionLabel").asText());
        jdbc.sql("update fob_purchase_product set updated_at=now(),version=version+1 where sku='SKU19'").update();
        mvc.perform(post("/api/v1/quotations").session(sessions.get("employee")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(batch.toString())).andExpect(status().isConflict());
        assertEquals(before+1,jdbc.sql("select count(*) from quotation_record").query(Long.class).single());assertEquals(snapshot,read("employee",id).path("fob"));
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


    ObjectNode batchRecord() throws Exception {
        var batch=FobBatchQuotationTest.input(3);
        for(var item:batch.at("/fob/items")) {
            var p=(ObjectNode)item.path("product").deepCopy();p.put("weightRaw","60 g");
            jdbc.sql("insert into fob_purchase_product(sku,payload,version,created_at,updated_at) values(:sku,cast(:p as jsonb),0,now(),'2026-10-09T00:00:00Z') on conflict(sku) do update set payload=excluded.payload,updated_at=excluded.updated_at")
                .param("sku",p.path("sku").asText()).param("p",p.toString()).update();
        }
        return (ObjectNode)save("employee",UUID.randomUUID().toString(),batch);
    }
    ObjectNode versions(JsonNode row) {return mapper.createObjectNode().put("_version",row.path("_version").asLong()).put("_reviewVersion",row.path("_reviewVersion").asLong());}
    JsonNode claim(JsonNode row) throws Exception {
        var body=versions(row).put("action","claim");
        return mapper.readTree(mvc.perform(patch("/api/v1/quotations/"+row.path("id").asText()+"/finance-review").session(sessions.get("super_admin")).with(csrf()).contentType("application/json").content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
    }
    org.springframework.test.web.servlet.ResultActions correction(String role,JsonNode row,String action,ObjectNode body,String key) throws Exception {
        return mvc.perform(post("/api/v1/quotations/"+row.path("id").asText()+"/fob-corrections/"+action).session(sessions.get(role)).with(csrf()).header("Idempotency-Key","test-key-"+key).contentType("application/json").content(body.toString()));
    }
    JsonNode returned(JsonNode row) throws Exception {
        var claimed=claim(row);var body=versions(claimed).put("action","return").put("approveOthers",true);
        body.putArray("items").addObject().put("index",0).put("reason","采购克重不对，请核实");
        body.withArray("items").addObject().put("index",1).put("reason","采购价格异常，自定义原因");
        correction("super_admin",row,"review",body,"unused").andExpect(status().isOk());return read("employee",row.path("id").asText());
    }
    ObjectNode fresh(JsonNode row,int index) throws Exception {
        String sku=row.at("/fob/items/"+index+"/product/sku").asText();
        jdbc.sql("update fob_purchase_product set updated_at='2026-10-11T12:00:00Z',version=version+1 where sku=:sku").param("sku",sku).update();
        var item=(ObjectNode)row.at("/fob/items/"+index).deepCopy();((ObjectNode)item.path("product")).put("updatedAt","2026-10-11T12:00:00Z");return item;
    }
    String originalPayload(JsonNode row) {return jdbc.sql("select payload::text from quotation_record where id=cast(:id as uuid)").param("id",row.path("id").asText()).query(String.class).single();}
    @Test void itemReturnPartialCorrectionAndReapprovalPreserveOriginalAndOtherAccountsAgree() throws Exception {
        var original=batchRecord();var raw=originalPayload(original);var row=returned(original);var approved=row.at("/fobItemReviews/2").deepCopy();
        assertEquals("approved",approved.path("status").asText());assertEquals("采购克重不对，请核实",row.at("/fobItemReviews/0/reason").asText());
        var inbox=mapper.readTree(mvc.perform(get("/api/v1/review-notifications").session(sessions.get("employee"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals("fob-return",inbox.at("/data/items/0/kind").asText());
        var body=versions(row);body.putArray("items").addObject().put("index",0).set("snapshot",fresh(row,0));var key=UUID.randomUUID().toString();
        correction("employee",row,"submit",body,key).andExpect(status().isOk());
        correction("employee",row,"submit",body,key).andExpect(status().isOk());
        var updated=read("employee",row.path("id").asText());
        assertEquals(1,updated.path("fobRevision").asInt());assertEquals("pending",updated.path("financeReviewStatus").asText());
        assertEquals("pending",updated.at("/fobItemReviews/0/status").asText());assertEquals("rejected",updated.at("/fobItemReviews/1/status").asText());
        var queue=mapper.readTree(mvc.perform(get("/api/v1/quotations/search").param("scope","company").param("q",row.path("no").asText()).param("reviewStatus","pending").session(sessions.get("super_admin"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(row.path("id").asText(),queue.at("/data/items/0/id").asText());assertEquals(updated.path("fob"),queue.at("/data/items/0/fob"));
        assertEquals(approved,updated.at("/fobItemReviews/2"));assertEquals(original.at("/fob/items/2"),updated.at("/fob/items/2"));assertEquals(raw,originalPayload(original));
        var deal=mapper.createObjectNode().put("_version",updated.path("_version").asLong()).put("status","won").put("closedAt","2026-10-11").put("actualQuoteUsd",3.04).put("dealQuantity",2);
        deal.putArray("dealLines").addObject().put("id","deal").put("optionId","fob:0:declared").put("unitPriceUsd",1.52).put("quantity",2).put("amountUsd",3.04);
        mvc.perform(patch("/api/v1/quotations/"+row.path("id").asText()).session(sessions.get("employee")).with(csrf()).contentType("application/json").content(deal.toString())).andExpect(status().isConflict());
        for(String role:List.of("super_admin","finance"))assertEquals(updated.path("fob"),read(role,row.path("id").asText()).path("fob"));
        var oldVersion=mapper.readTree(mvc.perform(get("/api/v1/quotations/"+row.path("id").asText()+"/fob-corrections/revisions/0").session(sessions.get("employee"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");
        assertEquals(original.path("fob"),oldVersion);
        var revision=mapper.readTree(mvc.perform(get("/api/v1/quotations/"+row.path("id").asText()+"/fob-corrections/revisions/1").session(sessions.get("finance"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).path("data");assertEquals(updated.path("fob"),revision);
        var claimed=claim(updated);var approval=versions(claimed).put("action","approve");approval.putArray("items").addObject().put("index",1);
        correction("super_admin",row,"review",approval,"x").andExpect(status().isConflict());
        ((ObjectNode)approval.at("/items/0")).put("index",0);correction("super_admin",row,"review",approval,"x").andExpect(status().isOk());
        var finalRow=read("employee",row.path("id").asText());assertEquals("approved",finalRow.at("/fobItemReviews/0/status").asText());assertEquals(approved,finalRow.at("/fobItemReviews/2"));assertEquals(raw,originalPayload(original));
        assertEquals(1L,jdbc.sql("select count(*) from audit_log where action='quotation.review.fob-resubmit' and resource_id=:id").param("id",row.path("id").asText()).query(Long.class).single());
    }
    @Test void correctionRejectsUnchangedTamperedMissingStaleAndForeignRequestsAtomically() throws Exception {
        var original=batchRecord();var raw=originalPayload(original);var row=returned(original);var body=versions(row);
        body.putArray("items").addObject().put("index",0).set("snapshot",row.at("/fob/items/0"));
        correction("employee",row,"submit",body,"unchanged").andExpect(status().isConflict());
        correction("employee2",row,"submit",body,"foreign").andExpect(status().isForbidden());
        correction("purchase",row,"submit",body,"purchase").andExpect(status().isForbidden());
        var newer=fresh(row,0);((ObjectNode)body.at("/items/0")).set("snapshot",newer);
        var tampered=body.deepCopy();((ArrayNode)tampered.at("/items/0/snapshot/sheet/rows/0/prices")).set(0,DoubleNode.valueOf(.01));
        correction("employee",row,"submit",tampered,"tamper").andExpect(status().isConflict());
        var changedSku=body.deepCopy();((ObjectNode)changedSku.at("/items/0/snapshot/product")).put("sku","SKU2");
        correction("employee",row,"submit",changedSku,"sku").andExpect(status().isUnprocessableEntity());
        // A good first item followed by a deleted SKU must not persist a partial revision.
        body.withArray("items").addObject().put("index",1).set("snapshot",row.at("/fob/items/1"));
        jdbc.sql("delete from fob_purchase_product where sku='SKU1'").update();
        correction("employee",row,"submit",body,"missing").andExpect(status().isNotFound());
        var unchanged=read("employee",row.path("id").asText());assertFalse(unchanged.has("fobRevision"));assertEquals(row.path("fobItemReviews"),unchanged.path("fobItemReviews"));assertEquals(raw,originalPayload(original));
        ((ArrayNode)body.path("items")).remove(1);
        jdbc.sql("update fob_purchase_product set updated_at='2026-10-11T13:00:00Z',version=version+1 where sku='SKU0'").update();
        correction("employee",row,"submit",body,"stale-source").andExpect(status().isConflict());
        body.put("_reviewVersion",999);correction("employee",row,"submit",body,"stale-review").andExpect(status().isConflict());
    }
    @Test void itemReviewNeedsClaimAndReasonAndCannotFallBackToGlobalCompletion() throws Exception {
        var row=batchRecord();var body=versions(row).put("action","return");body.putArray("items").addObject().put("index",0).put("reason","原因");
        correction("super_admin",row,"review",body,"x").andExpect(status().isConflict());
        var claimed=claim(row);body=versions(claimed).put("action","return");body.putArray("items").addObject().put("index",0).put("reason","  ");
        correction("super_admin",row,"review",body,"x").andExpect(status().isUnprocessableEntity());
        correction("finance",row,"review",body,"x").andExpect(status().isConflict());
        correction("employee",row,"review",body,"x").andExpect(status().isForbidden());
        var global=versions(claimed).put("action","complete").put("financeReviewStatus","approved");
        mvc.perform(patch("/api/v1/quotations/"+row.path("id").asText()+"/finance-review").session(sessions.get("super_admin")).with(csrf()).contentType("application/json").content(global.toString())).andExpect(status().isUnprocessableEntity());
    }


    @Test void concurrentCorrectionRetriesProduceOneRevisionAndSourceFinanceChangesBlockCommit() throws Exception {
        var row=returned(batchRecord());var body=versions(row);body.putArray("items").addObject().put("index",0).set("snapshot",fresh(row,0));
        jdbc.sql("update finance_setting set payload=cast(:p as jsonb),version=version+1 where setting_key='exchange-rate'").param("p","{\"usdCny\":7}").update();
        correction("employee",row,"submit",body,"rate-changed").andExpect(status().isConflict());
        jdbc.sql("update finance_setting set payload=cast(:p as jsonb),version=version+1 where setting_key='exchange-rate'").param("p","{\"usdCny\":6.7}").update();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(3);String key=UUID.randomUUID().toString();
        try {
            var start=new java.util.concurrent.CountDownLatch(1);var jobs=new ArrayList<java.util.concurrent.Future<Integer>>();
            for(int i=0;i<3;i++)jobs.add(executor.submit(()->{start.await();return correction("employee",row,"submit",body.deepCopy(),key).andReturn().getResponse().getStatus();}));
            start.countDown();for(var job:jobs)assertEquals(200,job.get(30,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,read("employee",row.path("id").asText()).path("fobRevision").asInt());
        } finally {executor.shutdownNow();}
    }


    @Test void revisedProcurementPriceRecalculatesSurchargeBoundaryWithoutChangingOtherSkus() throws Exception {
        var row=returned(batchRecord());var item=fresh(row,0);
        jdbc.sql("update fob_purchase_product set payload=jsonb_set(payload,'{parsed,priceTiers,0,unitPriceCny}','8'),version=version+1 where sku='SKU0'").update();
        ((ObjectNode)item.at("/sheet/rows/0")).putArray("prices").add(1.67).add(1.70);
        ((ObjectNode)item.at("/sheet/rows/1")).putArray("prices").add(1.50).add(1.53);
        var body=versions(row);body.putArray("items").addObject().put("index",0).set("snapshot",item);
        correction("employee",row,"submit",body,"price-update").andExpect(status().isOk());
        var saved=read("employee",row.path("id").asText());
        assertEquals("1.50",saved.at("/fob/items/0/current/declaredUsd").asText());
        assertEquals(23,saved.at("/fob/items/0/ranges/1/minQty").asInt());
        assertEquals("23+ pcs",saved.at("/fob/sheet/rows/1/quantityRange").asText());
        assertEquals(row.at("/fob/items/1"),saved.at("/fob/items/1"));assertEquals(row.at("/fob/items/2"),saved.at("/fob/items/2"));
    }

}
