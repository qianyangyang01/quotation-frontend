package com.milano.quotation.quote;

import com.milano.quotation.security.QuotationPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
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

@SpringBootTest
@ActiveProfiles("test")
class QuotationLifecycleIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired QuotationRecordRepository records;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.milano.quotation.security.UserAccountRepository users;
    @Autowired com.milano.quotation.security.UserAccountService userService;
    @MockitoBean QuotationReadinessService readiness;
    @MockitoBean com.milano.quotation.logistics.LogisticsQuotationGuard logisticsGuard;
    MockMvc mvc;
    QuotationPrincipal employee, finance, admin;
    QuotationPrincipal principal(String account, String role) {
        if (users.findByAccountIgnoreCase(account).isEmpty())
            users.saveAndFlush(com.milano.quotation.security.UserAccount.create(account,account,"hash",role,false));
        return (QuotationPrincipal) userService.loadUserByUsername(account);
    }
    @BeforeEach void setup() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        employee=principal("ME","employee");finance=principal("LCFINANCE","finance");admin=principal("LCADMIN","super_admin");
        records.deleteAll(); jdbc.update("delete from audit_log");
    }
    QuotationRecordEntity record(String owner, String status, String review) {
        var row = new QuotationRecordEntity();row.id=UUID.randomUUID();row.quoteNo="QT-"+row.id.toString().substring(0,8);
        row.ownerAccount=owner;row.status=status;row.createdAt=Instant.now();row.updatedAt=row.createdAt;
        row.payload=mapper.createObjectNode().put("id",row.id.toString()).put("no",row.quoteNo).put("status",status)
                .put("customerName","测试报价").put("systemQuoteUsd",12.34).put("financeReviewStatus",review).put("salespersonAccount",owner);
        return records.saveAndFlush(row);
    }
    String body(String action, QuotationRecordEntity... rows) throws Exception {
        return mapper.writeValueAsString(Map.of("action",action,"reason","测试数据清理",
                "items",Arrays.stream(rows).map(r -> Map.of("id",r.id,"version",r.version)).toList()));
    }
    void change(String action, QuotationPrincipal principal, int status, QuotationRecordEntity... rows) throws Exception {
        mvc.perform(post("/api/v1/quotations/lifecycle").with(user(principal)).with(csrf()).contentType("application/json")
                .content(body(action,rows))).andExpect(status().is(status));
    }
    QuotationRecordEntity reload(QuotationRecordEntity row) { return records.findById(row.id).orElseThrow(); }

    @Test void archiveTrashAndRestorePreserveSnapshotsAndAnalyticsMembership() throws Exception {
        var row=record("ME","pending","pending"); var original=row.payload.deepCopy();
        change("archive",employee,200,row);
        var archived=reload(row);assertEquals("archived",archived.lifecycleState);assertEquals(row.version+1,archived.version);
        mvc.perform(get("/api/v1/quotations?scope=company").with(user(admin))).andExpect(jsonPath("$.data.total").value(1));
        change("trash",employee,200,archived);
        var trash=reload(row);assertEquals("trashed",trash.lifecycleState);
        mvc.perform(get("/api/v1/quotations?scope=company").with(user(admin))).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/quotations/"+row.id).with(user(employee))).andExpect(jsonPath("$.data.lifecycleState").value("trashed"));
        change("restore",employee,200,trash);
        var restored=reload(row);assertEquals("archived",restored.lifecycleState);
        change("restore",employee,200,restored);
        var active=reload(row);assertEquals("active",active.lifecycleState);
        original.properties().forEach(e -> assertEquals(e.getValue(),active.payload.get(e.getKey())));
        assertEquals(4,active.payload.path("revisions").size());
        assertEquals(4,jdbc.queryForObject("select count(*) from audit_log where resource_id=?",Integer.class,row.id.toString()));
        mvc.perform(get("/api/v1/quotations?scope=mine").with(user(employee))).andExpect(jsonPath("$.data.total").value(1));
    }
    @Test void staleVersionOrMissingRecordLeavesTheWholeBatchUntouched() throws Exception {
        var first=record("ME","pending","pending");var second=record("ME","pending","pending");
        second.version=99;
        change("trash",employee,409,first,second);
        assertEquals("active",reload(first).lifecycleState);
        assertEquals(0,jdbc.queryForObject("select count(*) from audit_log",Integer.class));
        second.id=UUID.randomUUID();
        change("trash",employee,404,first,second);
        assertEquals("active",reload(first).lifecycleState);
    }
    @Test void employeesAndFinanceCannotManageOtherOwnersAndAdminCan() throws Exception {
        var mine=record("ME","pending","pending");var other=record("OTHER","pending","pending");
        change("trash",employee,403,mine,other);assertEquals("active",reload(mine).lifecycleState);
        change("trash",finance,403,other);
        change("trash",admin,200,mine,other);
        change("restore",employee,403,reload(mine));
        change("restore",admin,200,reload(mine),reload(other));
        mvc.perform(get("/api/v1/quotations/"+other.id).with(user(employee))).andExpect(status().isForbidden());
    }
    @Test void wonReviewedAndDealDataBlockTheEntireBatchIncludingAdmin() throws Exception {
        var normal=record("ME","pending","pending");
        for(var protectedRow:List.of(record("ME","won","pending"),record("ME","pending","approved"),record("ME","pending","rejected"))) {
            change("trash",admin,409,normal,protectedRow);change("archive",employee,409,protectedRow);
            assertEquals("active",reload(normal).lifecycleState);
        }
        var deal=record("ME","lost","pending");((ObjectNode)deal.payload).putArray("dealLines").addObject().put("quantity",1);deal=records.saveAndFlush(deal);
        change("trash",admin,409,deal);
    }
    @Test void archivedAndTrashedRecordsCannotBeEditedOrReviewed() throws Exception {
        var row=record("ME","pending","pending");change("archive",employee,200,row);var saved=reload(row);
        mvc.perform(patch("/api/v1/quotations/"+row.id).with(user(employee)).with(csrf()).contentType("application/json")
            .content("{\"_version\":"+saved.version+",\"note\":\"changed\"}")).andExpect(status().isConflict());
        mvc.perform(patch("/api/v1/quotations/"+row.id+"/finance-review").with(user(admin)).with(csrf()).contentType("application/json")
            .content("{\"_version\":"+saved.version+",\"financeReviewStatus\":\"approved\"}")).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/quotations/review-status?ids="+row.id).with(user(employee)))
            .andExpect(jsonPath("$.data[0].lifecycleState").value("archived"));
    }
    @Test void validationCsrfAndRetryDoNotProducePartialChanges() throws Exception {
        var row=record("ME","pending","pending");
        mvc.perform(post("/api/v1/quotations/lifecycle").with(user(employee)).contentType("application/json").content(body("trash",row))).andExpect(status().isForbidden());
        change("delete",employee,422,row);change("trash",employee,422,row,row);
        mvc.perform(post("/api/v1/quotations/lifecycle").with(user(employee)).with(csrf()).contentType("application/json")
            .content(body("trash",row).replace("测试数据清理"," "))).andExpect(status().isUnprocessableEntity());
        change("trash",employee,200,row);change("trash",employee,409,row);
        assertEquals(1,jdbc.queryForObject("select count(*) from audit_log",Integer.class));
        change("restore",employee,200,reload(row));
    }
    @Test void concurrentCleanupWithSameVersionHasExactlyOneWinner() throws Exception {
        var row=record("ME","pending","pending");var requestBody=body("trash",row);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> operation=()->{
                start.await();
                return mvc.perform(post("/api/v1/quotations/lifecycle").with(user(employee)).with(csrf())
                    .contentType("application/json").content(requestBody)).andReturn().getResponse().getStatus();
            };
            var first=pool.submit(operation);var second=pool.submit(operation);start.countDown();
            var outcomes=new ArrayList<>(List.of(first.get(20,java.util.concurrent.TimeUnit.SECONDS),second.get(20,java.util.concurrent.TimeUnit.SECONDS)));
            Collections.sort(outcomes);assertEquals(List.of(200,409),outcomes);
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from audit_log",Integer.class));
        assertEquals("trashed",reload(row).lifecycleState);
    }
}
