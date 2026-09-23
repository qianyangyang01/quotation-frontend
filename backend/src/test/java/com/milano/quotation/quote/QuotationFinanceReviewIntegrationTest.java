package com.milano.quotation.quote;

import com.milano.quotation.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest @ActiveProfiles("test")
class QuotationFinanceReviewIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired QuotationRecordRepository records;
    @Autowired QuotationReviewRepository reviews;
    @Autowired UserAccountRepository users;
    @Autowired UserAccountService userService;
    @MockitoBean QuotationReadinessService readiness;
    @MockitoBean com.milano.quotation.logistics.LogisticsQuotationGuard logisticsGuard;
    MockMvc mvc; String owner; RequestPostProcessor admin,employee,other,finance,purchase;
    @BeforeEach void setup() {
        mvc=webAppContextSetup(context).apply(springSecurity()).build();owner="E"+UUID.randomUUID().toString().substring(0,8).toUpperCase();
        employee=login(owner,"employee");admin=login("A"+owner,"super_admin");other=login("O"+owner,"employee");finance=login("F"+owner,"finance");purchase=login("P"+owner,"purchase");
    }
    RequestPostProcessor login(String account,String role) {users.saveAndFlush(UserAccount.create(account,account,"hash",role,false));return user(userService.loadUserByUsername(account));}
    QuotationRecordEntity record() {
        var r=new QuotationRecordEntity();r.id=UUID.randomUUID();r.quoteNo="TEST-"+r.id.toString().substring(0,20);r.ownerAccount=owner;r.status="pending";r.createdAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);r.updatedAt=r.createdAt;
        var p=mapper.createObjectNode().put("id",r.id.toString()).put("no",r.quoteNo).put("status","pending").put("systemQuoteUsd",6.35).put("exchangeRate",6.7);
        p.putArray("quoteOptions").addObject().put("id","us").put("country","美国").put("carrier","4PX").put("channel","QC").put("quote1Usd",6.35);
        var sheet=p.putObject("sheetQuote");sheet.putArray("quantities").add(1);sheet.putArray("rows").addObject().put("optionId","us").putArray("prices").add(6.35);p.set("customerQuote",sheet.deepCopy());r.payload=p;return records.saveAndFlush(r);
    }
    ResultActions action(QuotationRecordEntity r,RequestPostProcessor actor,long qv,long rv,String action,String result,String note) throws Exception {
        var b=mapper.createObjectNode().put("_version",qv).put("_reviewVersion",rv).put("action",action).put("note",note);if(result!=null)b.put("financeReviewStatus",result);
        return mvc.perform(patch("/api/v1/quotations/{id}/finance-review",r.id).with(actor).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(b)));
    }
    JsonNode view(QuotationRecordEntity r) throws Exception {return mapper.readTree(mvc.perform(get("/api/v1/quotations/{id}",r.id).with(employee)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    long rv(QuotationRecordEntity r) {return reviews.findById(r.id).map(v->v.version).orElse(0L);}
    long qv(QuotationRecordEntity r) {return records.findById(r.id).orElseThrow().version;}
    void claim(QuotationRecordEntity r) throws Exception {action(r,finance,qv(r),rv(r),"claim",null,"").andExpect(status().isOk());}
    void complete(QuotationRecordEntity r) throws Exception {action(r,finance,qv(r),rv(r),"complete","approved","").andExpect(status().isOk());}
    void price(QuotationRecordEntity r,double value) throws Exception {
        var current=records.findById(r.id).orElseThrow();var p=mapper.createObjectNode().put("_version",current.version);var prices=(ObjectNode)current.payload.path("customerQuote").deepCopy();
        ((ObjectNode)prices.path("rows").get(0)).putArray("prices").add(value);p.set("customerQuote",prices);
        mvc.perform(patch("/api/v1/quotations/{id}",r.id).with(employee).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(p))).andExpect(status().isOk());
    }
    @Test void preservesBusinessPayloadVersionAndTimes() throws Exception {
        var r=record();claim(r);assertEquals("F"+owner,view(r).path("financeReviewClaimedAccount").asText());complete(r);
        var after=records.findById(r.id).orElseThrow();assertEquals(r.payload,after.payload);assertEquals(r.version,after.version);assertEquals(r.updatedAt,after.updatedAt);
        assertEquals("approved",view(r).path("financeReviewStatus").asText());assertEquals(2,reviews.findById(r.id).orElseThrow().state.path("history").size());
        mvc.perform(get("/api/v1/quotations/review-status").param("ids",r.id.toString()).with(employee)).andExpect(jsonPath("$.data[0]._reviewVersion").value(rv(r))).andExpect(jsonPath("$.data[0]._version").value(0));
    }
    @Test void fourAccountsOnlyOneWinner() throws Exception {
        var r=record();var accounts=new ArrayList<RequestPostProcessor>();for(int i=0;i<4;i++)accounts.add(login("F"+i+owner,"finance"));var barrier=new CyclicBarrier(4);
        try(var executor=Executors.newFixedThreadPool(4)) {
            var futures=java.util.stream.IntStream.range(0,4).mapToObj(i->executor.submit(()->{barrier.await(10,TimeUnit.SECONDS);return action(r,accounts.get(i),0,0,"claim",null,"").andReturn().getResponse().getStatus();})).toList();
            var codes=new ArrayList<Integer>();for(var f:futures)codes.add(f.get(30,TimeUnit.SECONDS));assertEquals(1,Collections.frequency(codes,200));assertEquals(3,Collections.frequency(codes,409));
        }
        assertEquals(1,reviews.findById(r.id).orElseThrow().state.path("history").size());
    }
    @Test void exclusiveCompletionCancellationAndAdminRelease() throws Exception {
        var r=record();claim(r);long old=rv(r);
        action(r,admin,0,old,"complete","approved","").andExpect(status().isForbidden());action(r,admin,0,old,"cancel",null,"").andExpect(status().isForbidden());
        action(r,finance,0,old,"release",null,"交接").andExpect(status().isForbidden());action(r,admin,0,old,"release",null,"").andExpect(status().isUnprocessableEntity());
        action(r,admin,0,old,"release",null,"交接").andExpect(status().isOk());claim(r);action(r,finance,0,old,"complete","approved","").andExpect(status().isConflict());
        action(r,finance,0,rv(r),"cancel",null,"").andExpect(status().isOk()).andExpect(jsonPath("$.data.financeReviewStatus").value("pending"));
    }
    @Test void readAndWritePermissionsStayScoped() throws Exception {
        var r=record();for(var a:List.of(employee,purchase,other))action(r,a,0,0,"claim",null,"").andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/quotations/{id}/review-history",r.id).with(other)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/quotations/review-status").param("ids",r.id.toString()).with(other)).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(patch("/api/v1/quotations/{id}",r.id).with(admin).with(csrf()).contentType("application/json").content("{\"_version\":0,\"note\":\"bad\"}")).andExpect(status().isForbidden());
    }
    @Test void priceChangesRetainClaimButInvalidateOldContentAndFinishedResult() throws Exception {
        var r=record();claim(r);long old=rv(r);price(r,7);assertEquals("reviewing",view(r).path("financeReviewStatus").asText());assertEquals("F"+owner,view(r).path("financeReviewClaimedAccount").asText());
        action(r,finance,0,old,"complete","approved","").andExpect(status().isConflict());action(r,finance,0,rv(r),"complete","approved","").andExpect(status().isConflict());
        complete(r);price(r,8);assertEquals("pending",view(r).path("financeReviewStatus").asText());assertFalse(view(r).has("financeReviewedBy"));assertEquals(4,reviews.findById(r.id).orElseThrow().state.path("history").size());
    }
    @Test void notesAndDealChangesKeepReviewAndClaimDoesNotBreakOwnerSave() throws Exception {
        var r=record();claim(r);mvc.perform(patch("/api/v1/quotations/{id}",r.id).with(employee).with(csrf()).contentType("application/json").content("{\"_version\":0,\"note\":\"备注\",\"status\":\"lost\"}")).andExpect(status().isOk());
        complete(r);long old=rv(r);price(r,6.35);assertEquals(old,rv(r));assertEquals("approved",view(r).path("financeReviewStatus").asText());
    }
    @Test void racingPriceEditCannotLeaveUnseenPriceApproved() throws Exception {
        var r=record();claim(r);long old=rv(r);var barrier=new CyclicBarrier(2);
        try(var e=Executors.newFixedThreadPool(2)) {
            var finish=e.submit(()->{barrier.await();return action(r,finance,0,old,"complete","approved","").andReturn().getResponse().getStatus();});
            var edit=e.submit(()->{barrier.await();price(r,7);return true;});assertTrue(edit.get(30,TimeUnit.SECONDS));assertTrue(Set.of(200,409).contains(finish.get(30,TimeUnit.SECONDS)));
        }assertNotEquals("approved",view(r).path("financeReviewStatus").asText());
    }
    @Test void preservesLegacyConclusionsAndRejectsOldDirectReviewApi() throws Exception {
        var r=record();((ObjectNode)r.payload).put("financeReviewStatus","approved").put("financeReviewedBy","历史审核人");r=records.saveAndFlush(r);assertEquals("approved",view(r).path("financeReviewStatus").asText());
        mvc.perform(patch("/api/v1/quotations/{id}/finance-review",r.id).with(finance).with(csrf()).contentType("application/json").content("{\"_version\":1,\"financeReviewStatus\":\"approved\"}")).andExpect(status().isUnprocessableEntity());
        claim(r);action(r,finance,qv(r),rv(r),"complete","rejected","").andExpect(status().isUnprocessableEntity());action(r,finance,qv(r),rv(r),"complete","rejected","价格需调整").andExpect(status().isOk());
    }
    @Test void rejectsForgedClaimOnNormalPatchAndCreate() throws Exception {
        var r=record();mvc.perform(patch("/api/v1/quotations/{id}",r.id).with(employee).with(csrf()).contentType("application/json").content("{\"_version\":0,\"financeReviewClaimedAccount\":\"forged\"}")).andExpect(status().isUnprocessableEntity());
        var input=PackagingWeightTest.valid();input.put("financeReviewStatus","reviewing").put("financeReviewClaimedAccount","forged").put("_reviewVersion",90);
        mvc.perform(post("/api/v1/quotations").with(employee).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(mapper.writeValueAsString(input))).andExpect(status().isOk()).andExpect(jsonPath("$.data.financeReviewStatus").value("pending")).andExpect(jsonPath("$.data.financeReviewClaimedAccount").doesNotExist()).andExpect(jsonPath("$.data._reviewVersion").value(0));
    }
}
