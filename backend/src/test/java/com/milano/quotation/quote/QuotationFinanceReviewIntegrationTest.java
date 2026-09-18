package com.milano.quotation.quote;

import com.milano.quotation.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
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
    @Autowired UserAccountRepository users;
    @Autowired UserAccountService userService;
    @MockitoBean QuotationReadinessService readiness;
    @MockitoBean com.milano.quotation.logistics.LogisticsQuotationGuard logisticsGuard;
    MockMvc mvc;
    String owner;
    org.springframework.test.web.servlet.request.RequestPostProcessor admin, employee, other, finance, purchase;
    @BeforeEach void setup() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        owner = "E"+UUID.randomUUID().toString().substring(0,8).toUpperCase();
        employee = login(owner,"employee"); admin = login("A"+owner,"super_admin");
        other = login("O"+owner,"employee"); finance = login("F"+owner,"finance"); purchase = login("P"+owner,"purchase");
    }
    org.springframework.test.web.servlet.request.RequestPostProcessor login(String account, String role) {
        users.saveAndFlush(UserAccount.create(account,account,"hash",role,false));
        return user(userService.loadUserByUsername(account));
    }
    QuotationRecordEntity record() {
        var row = new QuotationRecordEntity(); row.id=UUID.randomUUID(); row.quoteNo="TEST-"+row.id.toString().substring(0,20);
        row.ownerAccount=owner; row.status="pending"; row.createdAt=Instant.now(); row.updatedAt=row.createdAt;
        var p=mapper.createObjectNode().put("id",row.id.toString()).put("no",row.quoteNo).put("status","pending").put("systemQuoteUsd",6.35).put("exchangeRate",6.7);
        p.putArray("quoteOptions").addObject().put("id","us").put("country","美国").put("carrier","4PX").put("channel","QC").put("quote1Usd",6.35);
        var sheet=p.putObject("sheetQuote");sheet.putArray("quantities").add(1);sheet.putArray("rows").addObject().put("optionId","us").putArray("prices").add(6.35);
        p.set("customerQuote",sheet.deepCopy()); row.payload=p; return records.saveAndFlush(row);
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder review(UUID id,long version,String status) {
        return patch("/api/v1/quotations/{id}/finance-review",id).with(csrf()).contentType("application/json")
                .content("{\"_version\":"+version+",\"financeReviewStatus\":\""+status+"\"}");
    }
    @Test void adminReviewsEmployeeRecordAndEmployeeSeesOnlyTheirOwnStatusAndCannotForgeIt() throws Exception {
        var row=record(); var old=row.payload.deepCopy();
        mvc.perform(review(row.id,row.version,"approved").with(employee)).andExpect(status().isForbidden());
        mvc.perform(review(row.id,row.version,"approved").with(purchase)).andExpect(status().isForbidden());
        mvc.perform(review(row.id,row.version,"approved").with(admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.financeReviewStatus").value("approved")).andExpect(jsonPath("$.data.financeReviewedAccount").value("A"+owner));
        mvc.perform(get("/api/v1/quotations/review-status").param("ids",row.id.toString()).with(employee)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].financeReviewStatus").value("approved")).andExpect(jsonPath("$.data[0]._version").value(1));
        mvc.perform(get("/api/v1/quotations/review-status").param("ids",row.id.toString()).with(other)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/v1/quotations/{id}",row.id).with(other)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/quotations/{id}",row.id).with(admin).with(csrf()).contentType("application/json").content("{\"_version\":1,\"note\":\"bad\"}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/quotations/{id}",row.id).with(employee).with(csrf()).contentType("application/json").content("{\"_version\":1,\"financeReviewStatus\":\"approved\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(review(row.id,0,"rejected").with(admin)).andExpect(status().isConflict());
        mvc.perform(review(row.id,1,"rejected").with(finance)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/quotations/{id}",row.id).with(employee)).andExpect(status().isOk()).andExpect(jsonPath("$.data.financeReviewStatus").value("rejected"));
        var saved=records.findById(row.id).orElseThrow().payload;
        for(var field:List.of("sheetQuote","customerQuote","systemQuoteUsd","quoteOptions","status")) assertEquals(old.path(field),saved.path(field));
        assertEquals(2,saved.path("revisions").size());
    }
    @Test void concurrentReviewWritersCommitOnlyOneAndAuditOnce() throws Exception {
        var row=record(); var barrier=new CyclicBarrier(8);
        try(var executor=Executors.newFixedThreadPool(8)) {
            var futures=java.util.stream.IntStream.range(0,8).mapToObj(i->CompletableFuture.supplyAsync(()->{
                try {barrier.await(15,TimeUnit.SECONDS);return mvc.perform(review(row.id,0,i%2==0?"approved":"rejected").with(admin)).andReturn().getResponse().getStatus();}
                catch(Exception e){throw new CompletionException(e);}
            },executor)).toList();
            var statuses=new ArrayList<Integer>();for(var f:futures)statuses.add(f.get(30,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(statuses,200));assertEquals(7,Collections.frequency(statuses,409));
        }
        assertEquals(1,records.findById(row.id).orElseThrow().payload.path("revisions").size());
    }
    @Test void priceChangesInvalidateReviewButNoteAndUnchangedPriceDoNot() throws Exception {
        var row=record(); mvc.perform(review(row.id,0,"approved").with(admin)).andExpect(status().isOk());
        var same=mapper.createObjectNode().put("_version",1);same.set("customerQuote",row.payload.path("customerQuote"));
        mvc.perform(patch("/api/v1/quotations/{id}",row.id).with(employee).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(same)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.financeReviewStatus").value("approved"));
        var current=records.findById(row.id).orElseThrow();
        mvc.perform(patch("/api/v1/quotations/{id}",row.id).with(employee).with(csrf()).contentType("application/json").content("{\"_version\":"+current.version+",\"note\":\"note\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.financeReviewStatus").value("approved"));
        current=records.findById(row.id).orElseThrow();same.put("_version",current.version);
        ((ObjectNode)same.path("customerQuote").path("rows").get(0)).putArray("prices").add(7);
        mvc.perform(patch("/api/v1/quotations/{id}",row.id).with(employee).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(same)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.financeReviewStatus").value("pending")).andExpect(jsonPath("$.data.financeReviewedAt").doesNotExist());
        mvc.perform(review(row.id,current.version,"approved").with(admin)).andExpect(status().isConflict());
    }
    @Test void simultaneousPriceEditAndReviewCannotApproveAnUnseenPrice() throws Exception {
        var row=record();var barrier=new CyclicBarrier(2);
        var pricePatch=mapper.createObjectNode().put("_version",0);pricePatch.set("customerQuote",row.payload.path("customerQuote").deepCopy());
        ((ObjectNode)pricePatch.path("customerQuote").path("rows").get(0)).putArray("prices").add(7);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var futures=java.util.stream.IntStream.range(0,2).mapToObj(i->CompletableFuture.supplyAsync(()->{
                try {barrier.await(10,TimeUnit.SECONDS);
                    var request=i==0?review(row.id,0,"approved").with(admin):patch("/api/v1/quotations/{id}",row.id).with(employee).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(pricePatch));
                    return mvc.perform(request).andReturn().getResponse().getStatus();
                }catch(Exception e){throw new CompletionException(e);}
            },executor)).toList();
            var codes=new ArrayList<Integer>();for(var f:futures)codes.add(f.get(30,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(codes,200));assertEquals(1,Collections.frequency(codes,409));
        }
        var saved=records.findById(row.id).orElseThrow().payload;
        if(saved.path("financeReviewStatus").asText().equals("approved")) assertEquals(6.35,saved.path("customerQuote").path("rows").get(0).path("prices").get(0).asDouble());
    }

    @Test void rejectsInvalidReviewPayloadAndForgedCreateReview() throws Exception {
        var row=record();mvc.perform(review(row.id,0,"bogus").with(admin)).andExpect(status().isUnprocessableEntity());
        mvc.perform(patch("/api/v1/quotations/{id}/finance-review",row.id).with(admin).with(csrf()).contentType("application/json").content("{\"_version\":0,\"financeReviewStatus\":\"approved\",\"systemQuoteUsd\":1}"))
            .andExpect(status().isUnprocessableEntity());
        var input=PackagingWeightTest.valid();input.put("financeReviewStatus","approved").put("financeReviewedBy","forged");
        mvc.perform(post("/api/v1/quotations").with(employee).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(mapper.writeValueAsString(input)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.financeReviewStatus").value("pending")).andExpect(jsonPath("$.data.financeReviewedBy").doesNotExist());
    }
}
