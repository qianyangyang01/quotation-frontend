package com.milano.quotation.quote;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.Test;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Run the same concurrent API/locking scenarios against the production database engine. */
@Testcontainers(disabledWithoutDocker=true)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class QuotationReviewPostgresIntegrationTest extends QuotationFinanceReviewIntegrationTest {
    @Test void prioritySortingFilteringAndCountsApplyBeforePagination() throws Exception {
        var old=record();old.createdAt=java.time.Instant.parse("2026-10-01T00:00:00Z");records.saveAndFlush(old);
        var newer=record();newer.createdAt=java.time.Instant.parse("2026-10-02T00:00:00Z");records.saveAndFlush(newer);
        var ordinary=record();
        priority(newer,employee,true,0).andExpect(status().isOk());
        priority(old,employee,true,0).andExpect(status().isOk());
        for (var only:java.util.List.of("false","true")) {
            mvc.perform(get("/api/v1/quotations/search").param("priorityOnly",only).param("size","1").param("page","0").with(employee))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(only.equals("true")?2:3))
                .andExpect(jsonPath("$.data.items[0].id").value(old.id.toString())).andExpect(jsonPath("$.data.items[0].priorityProcessing").value(true));
            mvc.perform(get("/api/v1/quotations/search").param("priorityOnly",only).param("size","1").param("page","1").with(employee))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(newer.id.toString()));
        }
        mvc.perform(get("/api/v1/quotations/search").param("size","1").param("page","2").with(employee))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(ordinary.id.toString()));
        mvc.perform(get("/api/v1/quotations/search").param("priorityOnly","true").param("scope","company").with(other))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        claim(old);complete(old);
        mvc.perform(get("/api/v1/quotations/search").param("priorityOnly","true").param("reviewStatus","pending").with(employee))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.total").value(1)).andExpect(jsonPath("$.data.items[0].id").value(newer.id.toString()));
    }
    @Test void searchIncludesSameOpinionForEmployeeAndFinanceWithinTheirScope() throws Exception {
        var r=record();
        action(r,finance,qv(r),rv(r),"comment",null,"建议核实包装重量").andExpect(status().isOk());
        for(var actor:java.util.List.of(employee,finance)) {
            mvc.perform(get("/api/v1/quotations/search").param("scope","company").param("q",r.quoteNo).with(actor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].financeReviewCommentCount").value(1))
                .andExpect(jsonPath("$.data.items[0].financeReviewLatestComment.note").value("建议核实包装重量"))
                .andExpect(jsonPath("$.data.items[0].financeReviewLatestComment.actorName").value("F"+owner))
                .andExpect(jsonPath("$.data.items[0].financeReviewLatestComment.at").isNotEmpty());
        }
        mvc.perform(get("/api/v1/quotations/search").param("scope","company").param("q",r.quoteNo).with(other))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }
    @Test void spotCheckFiltersCountAndPaginateWithinEmployeeScope() throws Exception {
        var checked=record();var unchecked=record();spotCheck(checked,admin,qv(checked)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/quotations/search").param("spotCheck","checked").param("size","1").with(employee))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].id").value(checked.id.toString())).andExpect(jsonPath("$.data.items[0].spotChecked").value(true));
        mvc.perform(get("/api/v1/quotations/search").param("spotCheck","unchecked").with(employee))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.total").value(1)).andExpect(jsonPath("$.data.items[0].id").value(unchecked.id.toString()));
        mvc.perform(get("/api/v1/quotations/search").param("scope","company").param("spotCheck","checked").with(other))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/quotations/search").param("spotCheck","invalid").with(employee)).andExpect(status().isUnprocessableEntity());
        setSpotCheck(checked,admin,false,rv(checked)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/quotations/search").param("spotCheck","checked").with(employee)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/quotations/search").param("spotCheck","unchecked").with(employee)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
    }
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.sql.init.mode",()->"never");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("spring.jpa.defer-datasource-initialization",()->"false");r.add("spring.flyway.enabled",()->"true");
    }
}
