package com.milano.quotation.supplierrecord;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@ActiveProfiles("test")
class SupplierRecordIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired SupplierRecordRepository records;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        records.deleteAll();
    }

    @Test
    @WithMockUser(username = "PURCHASE1", authorities = "PERM_purchase")
    void createsSearchesUpdatesAndDeletesIndependentRecordWithOptimisticLocking() throws Exception {
        var purchaseCountBefore = jdbc.queryForObject("select count(*) from purchase_product", Long.class);
        var createdResponse = mvc.perform(post("/api/v1/supplier-records").with(csrf())
                        .contentType("application/json")
                        .content(input("广州华盛服饰有限公司", "广州·十三行", "A级", 92, "0.03", "256800.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("广州华盛服饰有限公司"))
                .andExpect(jsonPath("$.data.taxPoint").value(0.03))
                .andExpect(jsonPath("$.data.createdBy").value("PURCHASE1"))
                .andReturn().getResponse().getContentAsString();

        var created = mapper.readTree(createdResponse).path("data");
        var id = created.path("id").asText();
        var version = created.path("version").asLong();

        mvc.perform(get("/api/v1/supplier-records")
                        .param("query", "华盛")
                        .param("industryBelt", "广州·十三行")
                        .param("rating", "A级"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].cooperationScore").value(92));

        var updatedResponse = mvc.perform(put("/api/v1/supplier-records/{id}", id).with(csrf())
                        .header("If-Match", version)
                        .contentType("application/json")
                        .content(input("广州华盛服饰有限公司", "广州·十三行", "A级", 95, "0.05", "300000.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cooperationScore").value(95))
                .andExpect(jsonPath("$.data.updatedBy").value("PURCHASE1"))
                .andReturn().getResponse().getContentAsString();
        var updatedVersion = mapper.readTree(updatedResponse).path("data").path("version").asLong();

        mvc.perform(put("/api/v1/supplier-records/{id}", id).with(csrf())
                        .header("If-Match", version)
                        .contentType("application/json")
                        .content(input("过期覆盖", "", "待评价", null, null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        mvc.perform(delete("/api/v1/supplier-records/{id}", id).with(csrf())
                        .header("If-Match", updatedVersion))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/supplier-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        var purchaseCountAfter = jdbc.queryForObject("select count(*) from purchase_product", Long.class);
        org.junit.jupiter.api.Assertions.assertEquals(purchaseCountBefore, purchaseCountAfter);
        org.junit.jupiter.api.Assertions.assertEquals(1L, jdbc.queryForObject(
                "select count(*) from audit_log where action='supplier-record.create'", Long.class));
        org.junit.jupiter.api.Assertions.assertEquals(1L, jdbc.queryForObject(
                "select count(*) from audit_log where action='supplier-record.delete'", Long.class));
    }

    @Test
    @WithMockUser(username = "PURCHASE1", authorities = "PERM_purchase")
    void rejectsBlankNamesAndInvalidNumericRanges() throws Exception {
        mvc.perform(post("/api/v1/supplier-records").with(csrf())
                        .contentType("application/json")
                        .content(input(" ", "", "待评价", 101, "1.1", "-1")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @WithMockUser(username = "EMPLOYEE1", authorities = "PERM_quote")
    void quoteOnlyUsersCannotReadSupplierRecords() throws Exception {
        mvc.perform(get("/api/v1/supplier-records"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anonymousUsersCannotReadSupplierRecords() throws Exception {
        mvc.perform(get("/api/v1/supplier-records"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private static String input(String name, String industryBelt, String rating, Integer score,
                                String taxPoint, String monthlyPurchaseAmount) {
        return """
                {
                  "name": %s,
                  "industryBelt": %s,
                  "bossName": "张老板",
                  "contactDetails": "13800000000 / wx-huasheng",
                  "invoiceType": "普票",
                  "taxPoint": %s,
                  "qualityGrade": "A",
                  "deliveryTerms": "3-7天",
                  "capacityOrder": "5000件/天",
                  "stockingStrategy": "安全库存备货",
                  "alternativeInquiry": "已询价3家",
                  "corporateAccount": "6222 ****",
                  "corporateBank": "中国银行广州分行",
                  "hotProductRecommendation": true,
                  "freeSample": true,
                  "afterSales": "支持7天内退换",
                  "cooperationScore": %s,
                  "rating": %s,
                  "monthlyPurchaseAmount": %s,
                  "notes": "响应快，配合度高",
                  "suggestion": "保持深度合作"
                }
                """.formatted(json(name), json(industryBelt), number(taxPoint), number(score), json(rating), number(monthlyPurchaseAmount));
    }

    private static String json(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static String number(Object value) { return value == null ? "null" : String.valueOf(value); }
}
