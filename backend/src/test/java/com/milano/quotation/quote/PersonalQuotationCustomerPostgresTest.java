package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers(disabledWithoutDocker=true)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PersonalQuotationCustomerPostgresTest extends QuotationFinanceReviewIntegrationTest {
    private static final String URL="/api/v1/personal-quotation-customers";
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.sql.init.mode",()->"never");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");r.add("spring.jpa.defer-datasource-initialization",()->"false");r.add("spring.flyway.enabled",()->"true");
    }
    JsonNode create(String name,String key,RequestPostProcessor who)throws Exception {
        return mapper.readTree(mvc.perform(post(URL).with(who).with(csrf()).header("Idempotency-Key",key).contentType("application/json").content(mapper.createObjectNode().put("name",name).toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void isolatesByUserIncludingAdministratorsAndNeverAcceptsPricingOrOwnership()throws Exception {
        var row=create("个人客户","create-one",employee);var id=row.path("id").asText();
        for(var who:List.of(other,admin,finance)) {
            mvc.perform(get(URL).with(who)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
            mvc.perform(put(URL+"/"+id).with(who).with(csrf()).contentType("application/json").content("{\"name\":\"覆盖\",\"_version\":0}")).andExpect(status().isNotFound());
            mvc.perform(delete(URL+"/"+id).with(who).with(csrf()).header("If-Match",0)).andExpect(status().isNotFound());
            mvc.perform(post(URL+"/"+id+"/use").with(who).with(csrf())).andExpect(status().isNotFound());
        }
        mvc.perform(get(URL).with(purchase)).andExpect(status().isForbidden());
        mvc.perform(post(URL).with(employee).header("Idempotency-Key","no-csrf").contentType("application/json").content("{\"name\":\"a\"}")).andExpect(status().isForbidden());
        for(var field:List.of("userId","ownerAccount","feeUsd","customerOperation","selectedCustomerId")) {
            mvc.perform(post(URL).with(employee).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(mapper.createObjectNode().put("name","a").put(field,"injected").toString())).andExpect(status().isUnprocessableEntity());
        }
        create("个人客户","other-same-name",other);
    }
    @Test void createsIdempotentlyRejectsDuplicatesAndInvalidNames()throws Exception {
        var one=create(" Alice ","retry-key",employee);assertEquals("Alice",one.path("name").asText());
        assertEquals(one,create(" Alice ","retry-key",employee));
        mvc.perform(post(URL).with(employee).with(csrf()).header("Idempotency-Key","duplicate-key").contentType("application/json").content("{\"name\":\"alice\"}")).andExpect(status().isConflict());
        for(var name:List.of("   ","x".repeat(121),"a\nb"))mvc.perform(post(URL).with(employee).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(mapper.createObjectNode().put("name",name).toString())).andExpect(status().isUnprocessableEntity());
        mvc.perform(get(URL).with(employee)).andExpect(jsonPath("$.data.length()").value(1));
    }
    @Test void versionedRenameAndRemovalPreserveQuotationSnapshotsAndReview()throws Exception {
        var quote=record();claim(quote);var before=records.findById(quote.id).orElseThrow().payload.deepCopy();var version=qv(quote);var review=rv(quote);
        var row=create("原客户","snapshot-key",employee);var id=row.path("id").asText();
        mvc.perform(put(URL+"/"+id).with(employee).with(csrf()).contentType("application/json").content("{\"name\":\"新客户\",\"_version\":0}")).andExpect(status().isOk()).andExpect(jsonPath("$.data._version").value(1));
        mvc.perform(put(URL+"/"+id).with(employee).with(csrf()).contentType("application/json").content("{\"name\":\"旧页面修改\",\"_version\":0}")).andExpect(status().isConflict());
        mvc.perform(delete(URL+"/"+id).with(employee).with(csrf()).header("If-Match",0)).andExpect(status().isConflict());
        mvc.perform(delete(URL+"/"+id).with(employee).with(csrf()).header("If-Match",1)).andExpect(status().isOk());
        mvc.perform(get(URL).with(employee)).andExpect(jsonPath("$.data.length()").value(0));
        assertEquals(before,records.findById(quote.id).orElseThrow().payload);assertEquals(version,qv(quote));assertEquals(review,rv(quote));
    }
    @Test void useMovesCustomerFirstWithoutChangingSavedName()throws Exception {
        var one=create("甲","use-one-key",employee);create("乙","use-two-key",employee);
        mvc.perform(post(URL+"/"+one.path("id").asText()+"/use").with(employee).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("甲")).andExpect(jsonPath("$.data.lastUsedAt").isNotEmpty());
        mvc.perform(get(URL).with(employee)).andExpect(jsonPath("$.data[0].id").value(one.path("id").asText()));
    }
    @Test void concurrentDuplicateCreatesAreSerialized()throws Exception {
        var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call=()->{barrier.await();return mvc.perform(post(URL).with(employee).with(csrf()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"name\":\"同一客户\"}")).andReturn().getResponse().getStatus();};
            var a=pool.submit(call);var b=pool.submit(call);var codes=new ArrayList<>(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)));Collections.sort(codes);assertEquals(List.of(200,409),codes);
        } finally {pool.shutdownNow();}
    }
}
