package com.milano.quotation.quote;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuotationWorkflowIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @MockitoBean QuotationReadinessService readiness;
    MockMvc mvc;

    @BeforeEach void setUp() { mvc = webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test
    void createsVoidsRestoresAndSharesSanitizedQuotation() throws Exception {
        var session = authenticatedSession();
        var created = mvc.perform(post("/api/v1/quotations").session(session).with(csrf())
                        .header("Idempotency-Key", "quote-test-1")
                        .contentType("application/json")
                        .content("{\"customerId\":\"11111111-1111-1111-1111-111111111111\",\"customerName\":\"测试客户\",\"productSummary\":\"测试商品\",\"purchaseCost\":\"SECRET_COST\",\"profitRate\":\"SECRET_PROFIT\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.customerId").doesNotExist()).andReturn();
        var createdData = mapper.readTree(created.getResponse().getContentAsByteArray()).path("data");
        var id = createdData.path("id").asText(); var version = createdData.path("_version").asLong();

        var voided = mvc.perform(post("/api/v1/quotations/{id}/void", id).session(session).with(csrf())
                        .contentType("application/json").content("{\"reason\":\"客户取消\",\"version\":" + version + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("voided")).andReturn();
        var voidVersion = mapper.readTree(voided.getResponse().getContentAsByteArray()).path("data").path("_version").asLong();

        var restored = mvc.perform(post("/api/v1/quotations/{id}/restore", id).session(session).with(csrf())
                        .contentType("application/json").content("{\"version\":" + voidVersion + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("pending")).andReturn();
        var restoredVersion = mapper.readTree(restored.getResponse().getContentAsByteArray()).path("data").path("_version").asLong();
        assertTrue(restoredVersion > voidVersion);

        var shared = mvc.perform(post("/api/v1/quotations/{id}/shares", id).session(session).with(csrf())
                        .contentType("application/json").content("{\"days\":7}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.token").isNotEmpty()).andReturn();
        var token = mapper.readTree(shared.getResponse().getContentAsByteArray()).path("data").path("token").asText();

        mvc.perform(get("/api/public/v1/quotation-shares/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerName").value("测试客户"))
                .andExpect(jsonPath("$.data.purchaseCost").doesNotExist())
                .andExpect(jsonPath("$.data.profitRate").doesNotExist());

        mvc.perform(get("/api/v1/quotations").session(session).param("scope", "company"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(id));

        mvc.perform(put("/api/v1/finance-settings/exchange-rate").session(session).with(csrf())
                        .header("If-Match", "-1").contentType("application/json")
                        .content("{\"usdToCny\":7.12,\"effectiveAt\":\"2026-08-22\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.value.usdToCny").value(7.12));

        mvc.perform(get("/api/v1/customers").session(session))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/quotation-drafts/mine").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"customerId\":\"11111111-1111-1111-1111-111111111111\",\"customerName\":\"草稿客户\",\"selectedCustomerGrade\":\"A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerName").value("草稿客户"))
                .andExpect(jsonPath("$.data.selectedCustomerGrade").value("A"))
                .andExpect(jsonPath("$.data.customerId").doesNotExist());

        var supplier = mvc.perform(post("/api/v1/suppliers").session(session).with(csrf()).contentType("application/json")
                        .content("{\"code\":\"SUP-001\",\"name\":\"供应商主数据\",\"contactName\":\"李四\",\"phone\":\"13900000000\",\"platform\":\"1688\",\"category\":\"服装\",\"settlementTerms\":\"月结30天\",\"leadTimeDays\":3,\"rating\":4.8,\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value("SUP-001")).andReturn();
        var supplierId = mapper.readTree(supplier.getResponse().getContentAsByteArray()).path("data").path("id").asText();
        mvc.perform(delete("/api/v1/suppliers/{id}", supplierId).session(session).with(csrf()))
                .andExpect(status().isOk());

        var migration = mvc.perform(post("/api/v1/migration-jobs/business/preview").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"schemaVersion\":1,\"sourceOrigin\":\"http://127.0.0.1:5173\",\"entries\":[{\"source\":\"localStorage\",\"container\":\"http://127.0.0.1:5173\",\"key\":\"purchase-products\",\"category\":\"purchase\",\"decision\":\"migrate\",\"reason\":\"approved candidate\",\"count\":1,\"value\":[{\"sku\":\"SKU-1\"}]}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("pending_review")).andReturn();
        var migrationId = mapper.readTree(migration.getResponse().getContentAsByteArray()).path("data").path("id").asText();
        mvc.perform(post("/api/v1/migration-jobs/business/{id}/approve", migrationId).session(session).with(csrf())
                        .contentType("application/json").content("{\"approvedEntryKeys\":[\"localStorage/purchase-products\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("approved"));
        mvc.perform(post("/api/v1/migration-jobs/business/preview").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"sourceOrigin\":\"http://127.0.0.1:5173\",\"entries\":[{\"decision\":\"migrate\",\"password\":\"must-not-pass\"}]}"))
                .andExpect(status().isUnprocessableEntity());
    }

    private MockHttpSession authenticatedSession() throws Exception {
        var login = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                        .content("{\"account\":\"ADMIN\",\"password\":\"TestAdmin123\"}"))
                .andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(post("/api/v1/auth/change-password").session(session).with(csrf()).contentType("application/json")
                        .content("{\"currentPassword\":\"TestAdmin123\",\"newPassword\":\"ChangedPass456\"}"))
                .andExpect(status().isOk());
        return session;
    }
}
