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
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.sql.init.mode",()->"never");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("spring.jpa.defer-datasource-initialization",()->"false");r.add("spring.flyway.enabled",()->"true");
    }
}
