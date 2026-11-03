package com.milano.quotation.finance;

import com.milano.quotation.security.*;
import com.milano.quotation.audit.AuditLogRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest @ActiveProfiles("test")
class WomensFinanceIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired FinanceSettingRepository settings;
    @Autowired UserAccountRepository users;
    @Autowired UserAccountService userService;
    @Autowired AuditLogRepository audit;
    @Autowired JdbcClient jdbc;
    MockMvc mvc; RequestPostProcessor admin, employee, finance;
    static final String KEY="womens-pricing", PATH="/api/v1/finance-settings/"+KEY;
    final WomensPricingTest fixture=new WomensPricingTest();
    @BeforeEach void setup() {
        mvc=webAppContextSetup(context).apply(springSecurity()).build();
        admin=login("super_admin"); employee=login("employee"); finance=login("finance");
        var row=settings.findById(KEY).orElseGet(()->FinanceSetting.create(KEY,fixture.policy()));
        row.payload=fixture.policy();settings.saveAndFlush(row);
    }
    RequestPostProcessor login(String role) {
        var account="WF"+UUID.randomUUID().toString().substring(0,8).toUpperCase();
        users.saveAndFlush(UserAccount.create(account,account,"hash",role,false));
        return user(userService.loadUserByUsername(account));
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder update(long version,String body) {
        return put(PATH).with(csrf()).header("If-Match",version).contentType("application/json").content(body);
    }
    @Test void financeCanPublishButEmployeeAnonymousMissingCsrfAndStaleVersionCannot() throws Exception {
        long version=settings.findById(KEY).orElseThrow().version;
        var changed=fixture.policy().toString().replace("1.27","1.28");
        mvc.perform(update(version,changed).with(employee)).andExpect(status().isForbidden());
        mvc.perform(update(version,changed)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH).with(finance).header("If-Match",version).contentType("application/json").content(changed)).andExpect(status().isForbidden());
        assertEquals(version,settings.findById(KEY).orElseThrow().version);
        mvc.perform(update(version,changed).with(finance)).andExpect(status().isOk()).andExpect(jsonPath("$.data._version").value(version+1));
        mvc.perform(update(version,fixture.policy().toString()).with(admin)).andExpect(status().isConflict());
        mvc.perform(get(PATH).with(employee)).andExpect(status().isOk()).andExpect(jsonPath("$.data.value.tiers[0].coefficient").value(1.28));
    }
    @Test void publishingAndDisablingPreserveHistoricalRecordsAndRecordBeforeAfterAudit() throws Exception {
        var ids=new ArrayList<UUID>();
        for(var mode:List.of("single","bundle")) {
            var body=(tools.jackson.databind.node.ObjectNode)mapper.readTree(getClass().getResourceAsStream("/womens-pricing/"+mode+".json"));
            body.put("status","won").put("financeReviewStatus","approved").put("financeReviewedBy","原审核人").put("financeReviewedAt","2026-11-01T01:00:00Z");
            var id=UUID.randomUUID();ids.add(id);
            jdbc.sql("insert into quotation_record(id,quote_no,owner_account,status,lifecycle_state,payload,version,created_at,updated_at) values(:id,:no,'HISTORY','won','active',:p format json,7,current_timestamp,current_timestamp)")
                .param("id",id).param("no","HISTORY-"+id.toString().substring(0,8)).param("p",body.toString()).update();
            var stored=mapper.readTree(jdbc.sql("select cast(payload as varchar) from quotation_record where id=:id").param("id",id).query(String.class).single());
            assertEquals(body,stored);
        }
        var before=jdbc.sql("select id,status,version,created_at,updated_at,cast(payload as varchar) as payload from quotation_record where id in (:ids) order by id").param("ids",ids).query().listOfRows();
        long version=settings.findById(KEY).orElseThrow().version;
        var previous=settings.findById(KEY).orElseThrow().payload.toString();
        var changed=fixture.policy().put("enabled",false).toString();
        mvc.perform(update(version,changed).with(finance)).andExpect(status().isOk());
        assertEquals(before,jdbc.sql("select id,status,version,created_at,updated_at,cast(payload as varchar) as payload from quotation_record where id in (:ids) order by id").param("ids",ids).query().listOfRows());
        var log=audit.findAll().stream().filter(a->KEY.equals(a.resourceId)&&a.detail.path("version").asLong()==version+1).findFirst().orElseThrow();
        assertEquals(previous,log.detail.path("before").asText()); assertEquals(changed,log.detail.path("after").asText());
        assertEquals("finance.update",log.action);assertEquals("success",log.outcome);assertNotEquals("anonymous",log.actorAccount);
    }
    @Test void rejectsMalformedPolicyWithoutAdvancingVersion() throws Exception {
        long version=settings.findById(KEY).orElseThrow().version;
        var policy=fixture.policy().toString();
        for(var body:List.of(policy.replace("1.27","0"),policy.replace("1.27","10.1"),policy.replace("1.27","1.1234567"),policy.replace("\"fromUsd\":20","\"fromUsd\":19"),policy.replace("\"toUsd\":null","\"toUsd\":100"),policy.replace("\"schemaVersion\":1","\"schemaVersion\":1.5"),policy.replace("\"schemaVersion\":1","\"schemaVersion\":\"1\"")))
            mvc.perform(update(version,body).with(admin)).andExpect(status().isUnprocessableEntity());
        assertEquals(version,settings.findById(KEY).orElseThrow().version);
    }
    @Test void concurrentPublicationsHaveExactlyOneWinner() throws Exception {
        long version=settings.findById(KEY).orElseThrow().version;var barrier=new CyclicBarrier(4);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<Integer>>();
            for(int i=0;i<4;i++){final int n=i;futures.add(pool.submit(()->{barrier.await(15,TimeUnit.SECONDS);return mvc.perform(update(version,fixture.policy().toString().replace("1.27","1."+(28+n))).with(admin)).andReturn().getResponse().getStatus();}));}
            var statuses=new ArrayList<Integer>();for(var f:futures)statuses.add(f.get(30,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(statuses,200));assertEquals(3,Collections.frequency(statuses,409));
        }
        assertEquals(version+1,settings.findById(KEY).orElseThrow().version);
    }
}
