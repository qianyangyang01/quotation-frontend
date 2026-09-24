package com.milano.quotation.quote;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers(disabledWithoutDocker=true)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class QuotationWithdrawalPostgresTest extends QuotationFinanceReviewIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.sql.init.mode",()->"never");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("spring.jpa.defer-datasource-initialization",()->"false");r.add("spring.flyway.enabled",()->"true");
    }
    @Autowired QuotationDraftRepository drafts;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    ObjectNode draft() {return mapper.createObjectNode().put("schemaVersion",2).put("customerName","撤回草稿").put("quoteMode","single").put("skuSearch","");}
    ObjectNode withdrawal(QuotationRecordEntity q) {var b=mapper.createObjectNode().put("_version",q.version);b.set("draft",draft());return b;}
    ObjectNode cancelBody(QuotationRecordEntity q) {var b=mapper.createObjectNode().put("_version",qv(q));drafts.findById(owner).ifPresent(d->b.put("draftVersion",d.version));return b;}
    ResultActions command(QuotationRecordEntity q,String action,ObjectNode body,String key,RequestPostProcessor actor) throws Exception {
        return mvc.perform(post("/api/v1/quotations/{id}/"+action,q.id).with(actor).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(body.toString()));
    }
    void withdraw(QuotationRecordEntity q) throws Exception {command(q,"withdraw",withdrawal(q),"withdraw-"+q.id,employee).andExpect(status().isOk()).andExpect(jsonPath("$.data.sourceQuote.no").value(q.quoteNo));}
    ObjectNode resubmission(QuotationRecordEntity q) {
        var b=cancelBody(q);var payload=PackagingWeightTest.valid().put("customerName","修改后客户");
        payload.putObject("purchaseVersions").put(payload.path("primarySku").asText(),"0:test");
        var financeVersions=payload.putObject("financeVersions");
        for(String setting:List.of("country-classification","channel-policies","customer-grades","exchange-rate","tax-settings","surcharge-settings","customer-operation-fees"))financeVersions.put(setting,0);
        b.set("quotation",payload);return b;
    }
    ResultActions saveDraft(long version,String source) throws Exception {
        var request=put("/api/v1/quotation-drafts/mine/state").with(employee).with(csrf()).header("If-Match",version).contentType("application/json").content(draft().put("customerName","另一个页面").toString());
        if(source!=null)request.header("X-Quotation-Source",source);return mvc.perform(request);
    }
    @Test void withdrawTerminatesReviewAndResubmitsSameNumberAtomically() throws Exception {
        var q=record();claim(q);var oldReview=rv(q);withdraw(q);
        command(q,"withdraw",withdrawal(q),"withdraw-"+q.id,employee).andExpect(status().isOk());
        assertEquals("withdrawn",records.findById(q.id).orElseThrow().lifecycleState);
        assertNull(reviews.findById(q.id).orElseThrow().claimantAccount);
        action(q,finance,0,oldReview,"complete","approved","").andExpect(status().isConflict());
        action(q,finance,qv(q),rv(q),"claim",null,"").andExpect(status().isConflict());
        mvc.perform(patch("/api/v1/quotations/{id}",q.id).with(employee).with(csrf()).contentType("application/json").content("{\"_version\":"+qv(q)+",\"note\":\"stale\"}")).andExpect(status().isConflict());
        saveDraft(0,null).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/quotation-drafts/mine/state").with(employee).with(csrf()).header("If-Match",0)).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/quotation-drafts/mine").with(employee).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/quotations/lifecycle").with(employee).with(csrf()).contentType("application/json").content("{\"action\":\"restore\",\"reason\":\"绕过\",\"items\":[{\"id\":\""+q.id+"\",\"version\":"+qv(q)+"}]}")).andExpect(status().isConflict());
        var invalid=resubmission(q);((ObjectNode)invalid.path("quotation")).put("customerName","");
        command(q,"resubmit",invalid,"bad-submit",employee).andExpect(status().isUnprocessableEntity());
        assertTrue(drafts.existsById(owner));assertEquals("withdrawn",records.findById(q.id).orElseThrow().lifecycleState);
        var unversioned=resubmission(q);((ObjectNode)unversioned.path("quotation")).remove("purchaseVersions");
        command(q,"resubmit",unversioned,"unversioned-submit",employee).andExpect(status().isUnprocessableEntity());
        assertTrue(drafts.existsById(owner));
        var body=resubmission(q);var response=command(q,"resubmit",body,"resubmit-once",employee).andExpect(status().isOk()).andExpect(jsonPath("$.data.no").value(q.quoteNo)).andExpect(jsonPath("$.data.status").value("pending")).andExpect(jsonPath("$.data.financeReviewStatus").value("pending")).andReturn().getResponse().getContentAsString();
        assertEquals(mapper.readTree(response).path("data"),mapper.readTree(command(q,"resubmit",body,"resubmit-once",employee).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data"));
        var saved=records.findById(q.id).orElseThrow();assertEquals(q.createdAt,saved.createdAt);assertEquals("active",saved.lifecycleState);assertFalse(drafts.existsById(owner));
        assertEquals("修改后客户",saved.payload.path("customerName").asText());assertEquals("quoteRevision",saved.payload.path("revisions").get(0).path("field").asText());
        action(q,finance,0,oldReview,"complete","approved","").andExpect(status().isConflict());
        saveDraft(0,q.id.toString()).andExpect(status().isConflict());
    }
    @Test void cancellationDeletesBodiesAndTombstonesOldCreateRetries() throws Exception {
        var input=PackagingWeightTest.valid();var key="create-"+UUID.randomUUID();
        var created=mapper.readTree(mvc.perform(post("/api/v1/quotations").with(employee).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(input.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        var q=records.findById(UUID.fromString(created.path("id").asText())).orElseThrow();claim(q);
        jdbc.sql("insert into logistics_quotation_history(quotation_id,snapshot) values(:id,cast(:body as jsonb))").param("id",q.id).param("body",q.payload.toString()).update();
        var body=cancelBody(q);command(q,"cancel",body,"cancel-once",employee).andExpect(status().isOk());
        command(q,"cancel",body,"cancel-once",employee).andExpect(status().isOk());
        assertFalse(records.existsById(q.id));assertFalse(reviews.existsById(q.id));
        assertEquals(0,jdbc.sql("select count(*) from logistics_quotation_history where quotation_id=:id").param("id",q.id).query(Integer.class).single());
        assertEquals(1,jdbc.sql("select count(*) from audit_log where resource_type='quotation' and resource_id=:id").param("id",q.id.toString()).query(Integer.class).single());
        mvc.perform(post("/api/v1/quotations").with(employee).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(input.toString())).andExpect(status().isGone());
        action(q,finance,0,1,"complete","approved","").andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/quotations/review-status").param("ids",q.id.toString()).with(finance)).andExpect(jsonPath("$.data").isEmpty());
    }
    @Test void ownershipDealsAndExistingDraftAreEnforcedWithoutPartialChanges() throws Exception {
        var q=record();claim(q);
        for(var actor:List.of(other,admin,finance))command(q,"cancel",cancelBody(q),"deny-owner",actor).andExpect(status().isForbidden());
        saveDraft(-1,null).andExpect(status().isOk());command(q,"withdraw",withdrawal(q),"draft-conflict",employee).andExpect(status().isConflict());
        assertEquals("reviewing",reviews.findById(q.id).orElseThrow().status);assertEquals("active",records.findById(q.id).orElseThrow().lifecycleState);
        drafts.deleteById(owner);
        for(boolean won:List.of(true,false)) {
            var blocked=record();if(won){blocked.status="won";((ObjectNode)blocked.payload).put("status","won");}else ((ObjectNode)blocked.payload).putArray("dealLines").addObject().put("quantity",1);
            blocked=records.saveAndFlush(blocked);command(blocked,"withdraw",withdrawal(blocked),"deny-withdraw",employee).andExpect(status().isConflict());command(blocked,"cancel",cancelBody(blocked),"deny-cancel",employee).andExpect(status().isConflict());
        }
    }
    @Test void reviewFirstThenWithdrawOrCancelInvalidatesItsResult() throws Exception {
        for(String operation:List.of("withdraw","cancel")) {var q=record();claim(q);complete(q);
            command(q,operation,operation.equals("withdraw")?withdrawal(q):cancelBody(q),"review-first",employee).andExpect(status().isOk());
            if(operation.equals("withdraw")){assertEquals("pending",reviews.findById(q.id).orElseThrow().status);command(q,"cancel",cancelBody(q),"cleanup-draft",employee).andExpect(status().isOk());}
            else assertFalse(records.existsById(q.id));
        }
    }
    List<Integer> race(Callable<Integer> a,Callable<Integer> b) throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {var first=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return a.call();});var second=pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);return b.call();});return List.of(first.get(45,TimeUnit.SECONDS),second.get(45,TimeUnit.SECONDS));}
    }
    @Test void racesReviewCompletionAndClaimAgainstBothCommands() throws Exception {
        for(String operation:List.of("withdraw","cancel"))for(String reviewAction:List.of("claim","complete")) {
            var q=record();if(reviewAction.equals("complete"))claim(q);long reviewVersion=rv(q);var body=operation.equals("withdraw")?withdrawal(q):cancelBody(q);
            var codes=race(()->command(q,operation,body,"race-command",employee).andReturn().getResponse().getStatus(),()->action(q,finance,0,reviewVersion,reviewAction,reviewAction.equals("complete")?"approved":null,"").andReturn().getResponse().getStatus());
            assertEquals(200,codes.get(0));assertTrue(Set.of(200,409,404).contains(codes.get(1)),codes.toString());
            if(operation.equals("withdraw")){assertEquals("pending",reviews.findById(q.id).orElseThrow().status);assertNull(reviews.findById(q.id).orElseThrow().claimantAccount);command(q,"cancel",cancelBody(q),"cleanup-draft",employee).andExpect(status().isOk());}else assertFalse(records.existsById(q.id));
        }
    }
    @Test void concurrentDraftCreationNeverOverwritesAnotherDraft() throws Exception {
        var q=record();var body=withdrawal(q);
        var codes=race(()->command(q,"withdraw",body,"race-draft",employee).andReturn().getResponse().getStatus(),()->saveDraft(-1,null).andReturn().getResponse().getStatus());
        assertEquals(1,Collections.frequency(codes,200));assertEquals(1,Collections.frequency(codes,409));
        var d=drafts.findById(owner).orElseThrow();assertEquals(d.sourceQuoteId==null?"active":"withdrawn",records.findById(q.id).orElseThrow().lifecycleState);
    }
    @Test void resubmitRacingAutosaveOrCancellationCannotLoseDraftOrResurrectRecord() throws Exception {
        for(String operation:List.of("save","cancel")) {
            var q=record();withdraw(q);var body=resubmission(q);var cancel=cancelBody(q);
            var codes=race(()->command(q,"resubmit",body,"race-submit",employee).andReturn().getResponse().getStatus(),()->operation.equals("cancel")?command(q,"cancel",cancel,"race-cancel",employee).andReturn().getResponse().getStatus():saveDraft(0,q.id.toString()).andReturn().getResponse().getStatus());
            assertEquals(1,Collections.frequency(codes,200),codes.toString());assertTrue(codes.stream().allMatch(c->Set.of(200,409,404).contains(c)),codes.toString());
            if(drafts.existsById(owner)){assertEquals("withdrawn",records.findById(q.id).orElseThrow().lifecycleState);command(q,"cancel",cancelBody(q),"cleanup-draft",employee).andExpect(status().isOk());}
            else if(records.existsById(q.id))assertEquals("active",records.findById(q.id).orElseThrow().lifecycleState);
        }
    }
    @Test void draftAndSubmissionOrderingsRejectStalePagesAndCancelledResubmitReplay() throws Exception {
        var q=record();withdraw(q);var stale=resubmission(q);
        saveDraft(0,q.id.toString()).andExpect(status().isOk());
        command(q,"resubmit",stale,"stale-draft-submit",employee).andExpect(status().isConflict());
        assertTrue(drafts.existsById(owner));
        var fresh=resubmission(q);command(q,"resubmit",fresh,"fresh-draft-submit",employee).andExpect(status().isOk());
        saveDraft(1,q.id.toString()).andExpect(status().isConflict());
        command(q,"cancel",cancelBody(q),"cancel-resubmitted",employee).andExpect(status().isOk());
        command(q,"resubmit",fresh,"fresh-draft-submit",employee).andExpect(status().isGone());
        assertFalse(records.existsById(q.id));assertFalse(drafts.existsById(owner));
        var second=record();withdraw(second);var pending=resubmission(second);
        command(second,"cancel",cancelBody(second),"cancel-before-submit",employee).andExpect(status().isOk());
        command(second,"resubmit",pending,"cancelled-submit",employee).andExpect(status().isNotFound());
        saveDraft(0,second.id.toString()).andExpect(status().isConflict());
    }
    @Test void failedDraftInsertRollsBackWithdrawalAndReviewRelease() throws Exception {
        var q=record();claim(q);var body=withdrawal(q);
        jdbc.sql("create function withdrawal_test_fail() returns trigger language plpgsql as $$ begin raise exception 'test draft storage failure'; end $$").update();
        jdbc.sql("create trigger withdrawal_test_fail before insert on quotation_draft for each row execute function withdrawal_test_fail()").update();
        try {
            assertTrue(command(q,"withdraw",body,"rollback-withdraw",employee).andReturn().getResponse().getStatus()>=400);
            assertEquals("active",records.findById(q.id).orElseThrow().lifecycleState);
            assertEquals("reviewing",reviews.findById(q.id).orElseThrow().status);
            assertFalse(drafts.existsById(owner));
        } finally {
            jdbc.sql("drop trigger withdrawal_test_fail on quotation_draft").update();
            jdbc.sql("drop function withdrawal_test_fail()").update();
        }
        command(q,"withdraw",body,"rollback-withdraw",employee).andExpect(status().isOk());
    }
    @Test void withdrawnRecordsAreExcludedFromListsAnalyticsAndExports() throws Exception {
      for(String operation:List.of("withdraw","cancel")) {
        var q=record();
        mvc.perform(get("/api/v1/quotations/search").with(employee)).andExpect(jsonPath("$.data.total").value(1));
        assertTrue(mvc.perform(get("/api/v1/quotations/analytics-records").with(finance)).andReturn().getResponse().getContentAsString().contains(q.id.toString()));
        command(q,operation,operation.equals("withdraw")?withdrawal(q):cancelBody(q),"exclude-"+operation,employee).andExpect(status().isOk());
        var search=mapper.readTree(mvc.perform(get("/api/v1/quotations/search").with(employee)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0,search.path("data").path("total").asInt());
        assertTrue(search.path("data").path("countries").isEmpty());
        assertFalse(mvc.perform(get("/api/v1/quotations/search").param("scope","company").with(finance)).andReturn().getResponse().getContentAsString().contains(q.id.toString()));
        var list=mvc.perform(get("/api/v1/quotations").with(employee)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertFalse(list.contains(q.id.toString()));
        var analytics=mvc.perform(get("/api/v1/quotations/analytics-records").with(finance)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertFalse(analytics.contains(q.id.toString()));
        if(operation.equals("withdraw"))command(q,"cancel",cancelBody(q),"exclude-cleanup",employee).andExpect(status().isOk());
      }
    }
    ResultActions edit(QuotationRecordEntity q, ObjectNode patchBody) throws Exception {
        return mvc.perform(patch("/api/v1/quotations/{id}",q.id).with(employee).with(csrf()).contentType("application/json").content(patchBody.toString()));
    }
    ObjectNode editBody(String kind) {
        var body=mapper.createObjectNode().put("_version",0);
        if(kind.equals("deal"))return body.put("status","won").put("dealQuantity",1).put("actualQuoteUsd",6.35);
        if(kind.equals("confirm"))return body.put("quoteConfirmed",true);
        var prices=body.putObject("customerQuote");prices.putArray("quantities").add(1);prices.putArray("rows").addObject().put("optionId","us").putArray("prices").add(7);
        return body;
    }
    @Test void editsAndDealsRaceWithWithdrawalAndCancellationUsingTheSameQuotationLock() throws Exception {
        for(String operation:List.of("withdraw","cancel"))for(String kind:List.of("price","confirm","deal")) {
            var q=record();var commandBody=operation.equals("withdraw")?withdrawal(q):cancelBody(q);var patchBody=editBody(kind);
            var codes=race(()->command(q,operation,commandBody,"race-update",employee).andReturn().getResponse().getStatus(),()->edit(q,patchBody).andReturn().getResponse().getStatus());
            assertEquals(1,Collections.frequency(codes,200),codes.toString());assertTrue(codes.stream().allMatch(c->Set.of(200,409,404).contains(c)),codes.toString());
            if(drafts.existsById(owner))command(q,"cancel",cancelBody(q),"cleanup-draft",employee).andExpect(status().isOk());
        }
    }
    @Test void bothEditOrderingsAreProtectedAndCompletedDealsNeverDisappear() throws Exception {
        for(String operation:List.of("withdraw","cancel"))for(String kind:List.of("price","confirm","deal")) {
            var first=record();var stale=operation.equals("withdraw")?withdrawal(first):cancelBody(first);
            edit(first,editBody(kind)).andExpect(status().isOk());
            command(first,operation,stale,"edit-first-stale",employee).andExpect(status().isConflict());
            if(kind.equals("deal")) {
                var fresh=records.findById(first.id).orElseThrow();command(first,operation,operation.equals("withdraw")?withdrawal(fresh):cancelBody(first),"edit-first-deal",employee).andExpect(status().isConflict());
                assertEquals("won",records.findById(first.id).orElseThrow().status);
            }
            var second=record();command(second,operation,operation.equals("withdraw")?withdrawal(second):cancelBody(second),"command-first",employee).andExpect(status().isOk());
            int code=edit(second,editBody(kind)).andReturn().getResponse().getStatus();assertTrue(Set.of(409,404).contains(code));
            if(drafts.existsById(owner))command(second,"cancel",cancelBody(second),"cleanup-draft",employee).andExpect(status().isOk());
        }
    }
    @Test void failedPhysicalDeletionRollsBackReviewSnapshotAndIdempotencyCleanup() throws Exception {
        var q=record();claim(q);
        jdbc.sql("create table withdrawal_test_reference (id uuid references quotation_record(id))").update();
        try {
            jdbc.sql("insert into withdrawal_test_reference values(:id)").param("id",q.id).update();
            var body=cancelBody(q);command(q,"cancel",body,"rollback-cancel",employee).andExpect(status().isConflict());
            assertTrue(records.existsById(q.id));assertEquals("reviewing",reviews.findById(q.id).orElseThrow().status);
            jdbc.sql("delete from withdrawal_test_reference").update();
            command(q,"cancel",body,"rollback-cancel",employee).andExpect(status().isOk());assertFalse(records.existsById(q.id));
        } finally {jdbc.sql("drop table withdrawal_test_reference").update();}
    }

}
