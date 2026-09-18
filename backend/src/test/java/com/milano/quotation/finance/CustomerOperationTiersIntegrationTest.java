package com.milano.quotation.finance;

import com.milano.quotation.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.web.context.WebApplicationContext;
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
class CustomerOperationTiersIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired FinanceSettingRepository settings;
    @Autowired UserAccountRepository users;
    @Autowired UserAccountService userService;
    MockMvc mvc;
    org.springframework.test.web.servlet.request.RequestPostProcessor admin, employee, finance;
    static final String KEY = "customer-operation-fees";
    static final String PATH = "/api/v1/finance-settings/" + KEY;
    static final String BODY = """
        {"customers":[{"id":"one","name":"客户","enabled":true,"feeUsd":0.3,"feesByQuantityUsd":{"1":0.3,"2":0.5,"3":0.7,"above3":0.8}}]}
        """;
    @BeforeEach void setup() {
        mvc=webAppContextSetup(context).apply(springSecurity()).build();
        admin=login("super_admin"); employee=login("employee"); finance=login("finance");
        var row=settings.findById(KEY).orElseGet(()->FinanceSetting.create(KEY,mapper.readTree(BODY)));
        row.payload=mapper.readTree(BODY); settings.saveAndFlush(row);
    }
    org.springframework.test.web.servlet.request.RequestPostProcessor login(String role) {
        var account="OT"+UUID.randomUUID().toString().substring(0,8).toUpperCase();
        users.saveAndFlush(UserAccount.create(account,account,"hash",role,false));
        return user(userService.loadUserByUsername(account));
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder update(long version,String body) {
        return put(PATH).with(csrf()).header("If-Match",version).contentType("application/json").content(body);
    }
    @Test void allRolesReadSameFeesButOnlyFinancePermissionWritesAndStaleClientsConflict() throws Exception {
        long version=settings.findById(KEY).orElseThrow().version;
        mvc.perform(update(version,BODY).with(employee)).andExpect(status().isForbidden());
        var changed=BODY.replace("0.8","0.9");
        mvc.perform(update(version,changed).with(admin)).andExpect(status().isOk());
        for(var identity:List.of(admin,employee,finance))
            mvc.perform(get(PATH).with(identity)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value.customers[0].feesByQuantityUsd.above3").value(.9));
        mvc.perform(update(version,BODY).with(finance)).andExpect(status().isConflict());
        var latest=settings.findById(KEY).orElseThrow();
        var legacy=mapper.readTree(BODY); ((tools.jackson.databind.node.ObjectNode)legacy.path("customers").get(0)).remove("feesByQuantityUsd");
        mvc.perform(update(latest.version,legacy.toString()).with(admin)).andExpect(status().isConflict());
        mvc.perform(update(latest.version,BODY).with(finance)).andExpect(status().isOk());
    }
    @Test void concurrentFinanceUpdatesHaveExactlyOneWinnerAndEmployeesSeeItsCompleteFourTiers() throws Exception {
        long version=settings.findById(KEY).orElseThrow().version;
        var barrier=new CyclicBarrier(8);
        try(var executor=Executors.newFixedThreadPool(8)) {
            var futures=java.util.stream.IntStream.range(0,8).mapToObj(i->CompletableFuture.supplyAsync(()->{
                try { barrier.await(15,TimeUnit.SECONDS); return mvc.perform(update(version,BODY.replace("0.8","0."+ (i+1))).with(admin)).andReturn().getResponse().getStatus(); }
                catch(Exception e){throw new CompletionException(e);}
            },executor)).toList();
            var statuses=new ArrayList<Integer>();for(var f:futures)statuses.add(f.get(30,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(statuses,200));assertEquals(7,Collections.frequency(statuses,409));
        }
        var saved=settings.findById(KEY).orElseThrow();
        assertEquals(version+1,saved.version);
        mvc.perform(get(PATH).with(employee)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.value.customers[0].feesByQuantityUsd").value(mapper.convertValue(saved.payload.path("customers").get(0).path("feesByQuantityUsd"),Map.class)));
    }
}
