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
        var body=mapper.createObjectNode().put("action",action).put("_version",record.path("_version").asLong()).put("_reviewVersion",record.path("_reviewVersion").asLong()).put("financeReviewStatus",result).put("note","逐条核实").put("exceptionType","other");
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
    @Test void copiesWholeBatchFromAnyMemberWithOwnerProtectionAndNoHistoryWrites() throws Exception {
        var result=save(UUID.randomUUID().toString(),ordinary(14));
        var last=result.path("records").get(13).path("id").asText();
        var first=result.path("records").get(0).path("id").asText();
        jdbc.sql("update quotation_record set lifecycle_state='archived' where id=cast(:id as uuid)").param("id",first).update();
        var before=jdbc.sql("select payload::text from quotation_record where payload->'quotationBatch'->>'id'=:id order by id").param("id",result.path("batchId").asText()).query(String.class).list();
        for(var role:List.of("employee","finance")) {
            var response=mvc.perform(get("/api/v1/quotations/"+last+"/batch-records").session(sessions.get(role))).andExpect(status().isOk()).andReturn();
            var members=mapper.readTree(response.getResponse().getContentAsByteArray()).path("data");
            assertEquals(14,members.size());assertEquals(first,members.get(0).path("id").asText());
            assertEquals(last,members.get(13).path("id").asText());assertEquals("archived",members.get(0).path("lifecycleState").asText());
        }
        mvc.perform(get("/api/v1/quotations/"+last+"/batch-records").session(sessions.get("employee2"))).andExpect(status().isForbidden());
        assertEquals(before,jdbc.sql("select payload::text from quotation_record where payload->'quotationBatch'->>'id'=:id order by id").param("id",result.path("batchId").asText()).query(String.class).list());
        jdbc.sql("update quotation_record set lifecycle_state='withdrawn' where id=cast(:id as uuid)").param("id",first).update();
        mvc.perform(get("/api/v1/quotations/"+last+"/batch-records").session(sessions.get("employee"))).andExpect(status().isConflict());
    }

    void seedFob(ObjectNode body) {
        jdbc.sql("insert into finance_setting(setting_key,payload,version,updated_at) values('exchange-rate','{\"usdCny\":6.7}'::jsonb,0,now()) on conflict(setting_key) do update set payload=excluded.payload").update();
        for(var item:body.at("/fob/items")) {
            var source=(ObjectNode)item.path("product").deepCopy();source.put("weightRaw","60 g");
            jdbc.sql("insert into fob_purchase_product(sku,payload,version,created_at,updated_at) values(:sku,cast(:p as jsonb),0,now(),'2026-10-09T00:00:00Z') on conflict(sku) do update set payload=excluded.payload,updated_at=excluded.updated_at")
                .param("sku",source.path("sku").asText()).param("p",source.toString()).update();
        }
    }
    ObjectNode trashRequest(JsonNode result) throws Exception {
        var request=mapper.createObjectNode().put("action","trash").put("reason","本批不再需要");
        var items=request.putArray("items");
        for(var saved:result.path("records")) {
            var row=read(saved.path("id").asText());items.addObject().put("id",row.path("id").asText()).put("version",row.path("_version").asLong());
        }
        return request;
    }
    org.springframework.test.web.servlet.ResultActions trash(String role,String member,ObjectNode request) throws Exception {
        return mvc.perform(post("/api/v1/quotations/lifecycle/batch/"+member).session(sessions.get(role)).with(csrf()).contentType("application/json").content(request.toString()));
    }
    ObjectNode trashPreview(String member) throws Exception {
        var response=mvc.perform(get("/api/v1/quotations/"+member+"/batch-records").param("purpose","trash").session(sessions.get("employee"))).andExpect(status().isOk()).andReturn();
        var request=mapper.createObjectNode().put("action","trash").put("reason","清理剩余记录");var items=request.putArray("items");
        for(var row:mapper.readTree(response.getResponse().getContentAsByteArray()).path("data")) items.addObject().put("id",row.path("id").asText()).put("version",row.path("_version").asLong());
        return request;
    }
    String storedRecord(String id) {
        return jdbc.sql("select row_to_json(q)::text from quotation_record q where id=cast(:id as uuid)").param("id",id).query(String.class).single();
    }
    @Test void withdrawnDraftAndPreviouslyTrashedRecordStayUnchangedWhileRemainingBatchIsTrashed() throws Exception {
        var result=save(UUID.randomUUID().toString(),ordinary(4));var first=read(result.at("/records/0/id").asText());
        var withdrawn=first.path("id").asText();var recycled=result.at("/records/1/id").asText();var last=result.at("/records/3/id").asText();
        var body=mapper.createObjectNode().put("_version",first.path("_version").asLong());
        body.set("draft",mapper.createObjectNode().put("schemaVersion",2).put("customerName","撤回草稿").put("quoteMode","single").put("skuSearch",""));
        mvc.perform(post("/api/v1/quotations/"+withdrawn+"/withdraw").session(sessions.get("employee")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body.toString())).andExpect(status().isOk());
        jdbc.sql("update quotation_record set lifecycle_state='trashed' where id=cast(:id as uuid)").param("id",recycled).update();
        jdbc.sql("update quotation_record set lifecycle_state='archived' where id=cast(:id as uuid)").param("id",result.at("/records/2/id").asText()).update();
        var skippedBefore=List.of(storedRecord(withdrawn),storedRecord(recycled));
        var draftBefore=jdbc.sql("select row_to_json(d)::text from quotation_draft d where source_quote_id=cast(:id as uuid)").param("id",withdrawn).query(String.class).single();
        var before=read(last);var request=trashPreview(last);assertEquals(4,request.path("items").size());
        mvc.perform(get("/api/v1/quotations/"+last+"/batch-records").session(sessions.get("employee"))).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/quotations/"+last+"/batch-records").param("purpose","trash").session(sessions.get("employee2"))).andExpect(status().isForbidden());
        trash("employee",last,request).andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(2));
        assertEquals(skippedBefore,List.of(storedRecord(withdrawn),storedRecord(recycled)));
        assertEquals(draftBefore,jdbc.sql("select row_to_json(d)::text from quotation_draft d where source_quote_id=cast(:id as uuid)").param("id",withdrawn).query(String.class).single());
        var after=read(last);assertEquals("trashed",after.path("lifecycleState").asText());assertEquals(before.path("customerQuote"),after.path("customerQuote"));assertEquals(before.path("systemQuoteUsd"),after.path("systemQuoteUsd"));
        assertEquals("archived",read(result.at("/records/2/id").asText()).path("lifecyclePreviousState").asText());
    }
    @Test void fobBatchSkipsRecycledMemberAndPreservesItsSnapshot() throws Exception {
        var body=BatchQuotationSubmissionTest.request(FobBatchQuotationTest.input(3));seedFob(body);var result=save(UUID.randomUUID().toString(),body);
        var first=result.at("/records/0/id").asText();var last=result.at("/records/2/id").asText();
        jdbc.sql("update quotation_record set lifecycle_state='trashed' where id=cast(:id as uuid)").param("id",first).update();
        var skipped=storedRecord(first);var snapshot=read(last).path("fob");
        trash("employee",last,trashPreview(last)).andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(2));
        assertEquals(skipped,storedRecord(first));assertEquals(snapshot,read(last).path("fob"));assertEquals("trashed",read(last).path("lifecycleState").asText());
    }
    @Test void resubmissionPreservesServerBatchMembershipAndDoesNotBreakRemainingCleanup() throws Exception {
        var result=save(UUID.randomUUID().toString(),ordinary(2));var first=read(result.at("/records/0/id").asText());var last=result.at("/records/1/id").asText();
        var id=first.path("id").asText();var withdrawal=mapper.createObjectNode().put("_version",first.path("_version").asLong());
        withdrawal.set("draft",mapper.createObjectNode().put("schemaVersion",2).put("customerName","撤回草稿").put("quoteMode","single").put("skuSearch",""));
        var response=mvc.perform(post("/api/v1/quotations/"+id+"/withdraw").session(sessions.get("employee")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(withdrawal.toString())).andExpect(status().isOk()).andReturn();
        var draft=mapper.readTree(response.getResponse().getContentAsByteArray()).path("data");
        var siblingBefore=storedRecord(last);
        var draftBefore=jdbc.sql("select row_to_json(d)::text from quotation_draft d where source_quote_id=cast(:id as uuid)").param("id",id).query(String.class).single();
        assertEquals(20,save(UUID.randomUUID().toString(),ordinary(20)).path("records").size());
        var fob=BatchQuotationSubmissionTest.request(FobBatchQuotationTest.input(20));seedFob(fob);
        assertEquals(20,save(UUID.randomUUID().toString(),fob).path("records").size());
        assertEquals(siblingBefore,storedRecord(last));
        assertEquals(draftBefore,jdbc.sql("select row_to_json(d)::text from quotation_draft d where source_quote_id=cast(:id as uuid)").param("id",id).query(String.class).single());
        var input=PackagingWeightTest.valid().put("customerName","重新编辑客户");
        input.putObject("purchaseVersions").put(input.path("primarySku").asText(),"0:test");input.putObject("financeVersions").put("exchange-rate",0);
        input.putObject("quotationBatch").put("id","forged").put("count",1).put("index",1);
        var request=mapper.createObjectNode().put("_version",draft.path("sourceQuote").path("version").asLong()).put("draftVersion",draft.path("version").asLong());request.set("quotation",input);
        mvc.perform(post("/api/v1/quotations/"+id+"/resubmit").session(sessions.get("employee")).with(csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(request.toString())).andExpect(status().isOk());
        assertEquals(first.path("quotationBatch"),read(id).path("quotationBatch"));
        assertEquals(siblingBefore,storedRecord(last));
        trash("employee",last,trashPreview(last)).andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(2));
    }
    @Test void skippedMemberRestoreOrRemainingReviewAfterPreviewStillRejectsWholeOperation() throws Exception {
        var result=save(UUID.randomUUID().toString(),ordinary(3));var first=result.at("/records/0/id").asText();var last=result.at("/records/2/id").asText();
        jdbc.sql("update quotation_record set lifecycle_state='trashed' where id=cast(:id as uuid)").param("id",first).update();
        var request=trashPreview(last);var partial=request.deepCopy();((ArrayNode)partial.path("items")).remove(0);
        trash("employee",last,partial).andExpect(status().isConflict());
        jdbc.sql("update quotation_record set lifecycle_state='active',version=version+1 where id=cast(:id as uuid)").param("id",first).update();
        trash("employee",last,request).andExpect(status().isConflict()).andExpect(content().string(org.hamcrest.Matchers.containsString("所选报价已变更")));
        assertEquals("active",read(first).path("lifecycleState").asText());
        jdbc.sql("update quotation_record set lifecycle_state='trashed',version=version+1 where id=cast(:id as uuid)").param("id",first).update();
        request=trashPreview(last);
        review(read(last),"claim","pending");trash("employee",last,request).andExpect(status().isConflict());
        assertEquals("active",read(result.at("/records/1/id").asText()).path("lifecycleState").asText());
    }
    void cancelRecord(String id) throws Exception {
        var record=read(id);
        mvc.perform(post("/api/v1/quotations/"+id+"/cancel").session(sessions.get("employee")).with(csrf())
            .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json")
            .content(mapper.createObjectNode().put("_version",record.path("_version").asLong()).toString())).andExpect(status().isOk());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void cancelledMembersDoNotBlockRemainingOrdinaryOrFobBatch(boolean fob) throws Exception {
        var body=fob?BatchQuotationSubmissionTest.request(FobBatchQuotationTest.input(4)):ordinary(4);
        if(fob)seedFob(body);
        var saved=save(UUID.randomUUID().toString(),body);
        var first=saved.at("/records/0/id").asText();var last=saved.at("/records/3/id").asText();
        var original=read(last);var unchanged=storedRecord(last);
        cancelRecord(saved.at("/records/1/id").asText());
        cancelRecord(saved.at("/records/2/id").asText());
        assertEquals(unchanged,storedRecord(last)); // No renumbering or rewriting saved snapshots.
        var preview=trashPreview(last);assertEquals(2,preview.path("items").size());
        mvc.perform(get("/api/v1/quotations/"+last+"/batch-records").session(sessions.get("employee"))).andExpect(status().isConflict());
        var partial=preview.deepCopy();((ArrayNode)partial.path("items")).remove(0);
        trash("employee",last,partial).andExpect(status().isConflict());
        trash("employee2",last,preview).andExpect(status().isForbidden());
        assertEquals(unchanged,storedRecord(last));
        trash("employee",last,preview).andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(2));
        assertEquals("trashed",read(first).path("lifecycleState").asText());
        var after=read(last);assertEquals(original.path("quotationBatch"),after.path("quotationBatch"));
        assertEquals(original.path("fob"),after.path("fob"));assertEquals(original.path("customerQuote"),after.path("customerQuote"));
    }
    @Test void cancellationAfterPreviewRejectsStaleScopeAndRefreshCanTrashLastSurvivor() throws Exception {
        var saved=save(UUID.randomUUID().toString(),ordinary(3));
        var first=saved.at("/records/0/id").asText();var second=saved.at("/records/1/id").asText();var last=saved.at("/records/2/id").asText();
        cancelRecord(first);var preview=trashPreview(last);var unchanged=storedRecord(last);
        cancelRecord(second);
        trash("employee",last,preview).andExpect(status().isNotFound());
        assertEquals(unchanged,storedRecord(last));
        trash("employee",last,trashPreview(last)).andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(1));
    }
    @Test void remainingProtectedMemberStillBlocksWholeBatchAfterCancellation() throws Exception {
        var saved=save(UUID.randomUUID().toString(),ordinary(3));
        var first=saved.at("/records/0/id").asText();var second=saved.at("/records/1/id").asText();var last=saved.at("/records/2/id").asText();
        cancelRecord(first);var preview=trashPreview(last);var unchanged=storedRecord(last);
        review(read(second),"claim","pending");
        trash("employee",last,preview).andExpect(status().isConflict());
        assertEquals(unchanged,storedRecord(last));
    }
    @Test void emptyRemainingBatchNeverWritesHistory() throws Exception {
        var result=save(UUID.randomUUID().toString(),ordinary(2));var first=result.at("/records/0/id").asText();var last=result.at("/records/1/id").asText();
        jdbc.sql("update quotation_record set lifecycle_state='trashed' where payload->'quotationBatch'->>'id'=:id").param("id",result.path("batchId").asText()).update();
        var before=List.of(storedRecord(first),storedRecord(last));
        trash("employee",last,trashPreview(last)).andExpect(status().isConflict());
        assertEquals(before,List.of(storedRecord(first),storedRecord(last)));
    }
    @Test void wholeBatchTrashFromLastMemberHidesBothQueuesAndBlocksStaleReviewWithoutChangingPrices() throws Exception {
        var result=save(UUID.randomUUID().toString(),ordinary(14));var request=trashRequest(result);
        var first=read(result.at("/records/0/id").asText());var last=result.at("/records/13/id").asText();
        trash("employee",last,request).andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(14));
        for(String role:List.of("employee","finance","super_admin")) {
            for(String reviewFilter:List.of("","pending","reviewing")) {
                mvc.perform(get("/api/v1/quotations/search").session(sessions.get(role)).param("scope",role.equals("employee")?"mine":"company")
                    .param("product",result.path("batchNo").asText()).param("reviewStatus",reviewFilter)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
            }
            mvc.perform(get("/api/v1/quotations/search").session(sessions.get(role)).param("scope",role.equals("employee")?"mine":"company")
                .param("product",result.path("batchNo").asText()).param("lifecycle","trashed")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(14));
        }
        for(String action:List.of("claim","complete","comment")) {
            mvc.perform(patch("/api/v1/quotations/"+first.path("id").asText()+"/finance-review").session(sessions.get("finance")).with(csrf()).contentType("application/json")
                .content(mapper.createObjectNode().put("action",action).put("_version",first.path("_version").asLong()).put("_reviewVersion",0).put("financeReviewStatus","approved").toString()))
                .andExpect(status().isConflict()).andExpect(content().string(org.hamcrest.Matchers.containsString("回收站")));
        }
        var after=read(first.path("id").asText());assertEquals(first.path("customerQuote"),after.path("customerQuote"));assertEquals(first.path("systemQuoteUsd"),after.path("systemQuoteUsd"));
        mvc.perform(get("/api/v1/quotations/review-status").param("ids",first.path("id").asText()).session(sessions.get("finance")))
            .andExpect(jsonPath("$.data[0].lifecycleState").value("trashed"));
        trash("employee",last,request).andExpect(status().isConflict());
    }
    @Test void incompleteForeignAndStaleBatchRequestsNeverPartiallyTrash() throws Exception {
        var result=save(UUID.randomUUID().toString(),ordinary(3));var request=trashRequest(result);var first=result.at("/records/0/id").asText();
        trash("employee2",first,request).andExpect(status().isForbidden());trash("finance",first,request).andExpect(status().isForbidden());
        var partial=request.deepCopy();((ArrayNode)partial.path("items")).remove(2);trash("employee",first,partial).andExpect(status().isConflict());
        var stale=request.deepCopy();((ObjectNode)stale.at("/items/2")).put("version",999);trash("employee",first,stale).andExpect(status().isConflict());
        var other=save(UUID.randomUUID().toString(),ordinary(1));var mixed=request.deepCopy();((ArrayNode)mixed.path("items")).set(2,trashRequest(other).at("/items/0"));
        trash("super_admin",first,mixed).andExpect(status().isConflict());
        for(var row:result.path("records"))assertEquals("active",read(row.path("id").asText()).path("lifecycleState").asText());
    }
    @Test void claimAfterConfirmationBlocksWholeBatchAndFobBatchCanBeTrashed() throws Exception {
        var result=save(UUID.randomUUID().toString(),ordinary(2));var request=trashRequest(result);var first=result.at("/records/0/id").asText();
        review(read(result.at("/records/1/id").asText()),"claim","pending");
        trash("employee",first,request).andExpect(status().isConflict());assertEquals("active",read(first).path("lifecycleState").asText());
        var fob=BatchQuotationSubmissionTest.request(FobBatchQuotationTest.input(2));seedFob(fob);var saved=save(UUID.randomUUID().toString(),fob);
        trash("super_admin",saved.at("/records/1/id").asText(),trashRequest(saved)).andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(2));
    }
    @Test void concurrentReviewAndWholeBatchTrashHaveOnlyOneWinner() throws Exception {
        var result=save(UUID.randomUUID().toString(),ordinary(3));var request=trashRequest(result);
        var first=read(result.at("/records/0/id").asText());var last=result.at("/records/2/id").asText();
        var reviewBody=mapper.createObjectNode().put("action","claim").put("_version",first.path("_version").asLong()).put("_reviewVersion",first.path("_reviewVersion").asLong());
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var cleanup=pool.submit(()->{start.await();return trash("employee",last,request).andReturn().getResponse().getStatus();});
            var claim=pool.submit(()->{start.await();return mvc.perform(patch("/api/v1/quotations/"+first.path("id").asText()+"/finance-review")
                .session(sessions.get("finance")).with(csrf()).contentType("application/json").content(reviewBody.toString())).andReturn().getResponse().getStatus();});
            start.countDown();int cleanupStatus=cleanup.get(30,java.util.concurrent.TimeUnit.SECONDS),claimStatus=claim.get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(cleanupStatus==200&&claimStatus==409 || cleanupStatus==409&&claimStatus==200);
            for(var row:result.path("records"))assertEquals(cleanupStatus==200?"trashed":"active",read(row.path("id").asText()).path("lifecycleState").asText());
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
