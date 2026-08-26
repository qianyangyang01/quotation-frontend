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
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.UUID;

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
    @Autowired QuotationRecordRepository records;
    @MockitoBean QuotationReadinessService readiness;
    MockMvc mvc;

    @BeforeEach void setUp() { mvc = webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test
    void keepsPersonalOutcomeEditingAndMakesCompanyRecordsReadOnly() throws Exception {
        var session = authenticatedSession();
        mvc.perform(post("/api/v1/quotations").session(session).with(csrf())
                        .header("Idempotency-Key", "quote-invalid-1").contentType("application/json").content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'customerName')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'monthlySalesEstimate')]").exists());
        var created = mvc.perform(post("/api/v1/quotations").session(session).with(csrf())
                        .header("Idempotency-Key", "quote-test-1")
                        .contentType("application/json")
                        .content("{\"customerId\":\"11111111-1111-1111-1111-111111111111\",\"customerName\":\"测试客户\",\"quoteMode\":\"single\",\"primarySku\":\"SKU-1\",\"productCategory\":\"服装\",\"logisticsAttribute\":\"普货\",\"customerGrade\":\"A级客户\",\"taxCustomerType\":\"A\",\"monthlySalesEstimate\":\"10\",\"quoteOptions\":[{\"id\":\"option-us\",\"country\":\"美国\",\"carrier\":\"承运商A\",\"channel\":\"渠道A\"},{\"id\":\"option-ca\",\"country\":\"加拿大\",\"carrier\":\"承运商B\",\"channel\":\"渠道B\"}],\"productSummary\":\"测试商品\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.customerId").doesNotExist()).andReturn();
        var createdData = mapper.readTree(created.getResponse().getContentAsByteArray()).path("data");
        var id = createdData.path("id").asText(); var version = createdData.path("_version").asLong();

        mvc.perform(post("/api/v1/quotations").session(session).with(csrf())
                        .header("Idempotency-Key", "quote-test-1")
                        .contentType("application/json")
                        .content("{\"customerId\":\"11111111-1111-1111-1111-111111111111\",\"customerName\":\"测试客户\",\"quoteMode\":\"single\",\"primarySku\":\"SKU-1\",\"productCategory\":\"服装\",\"logisticsAttribute\":\"普货\",\"customerGrade\":\"A级客户\",\"taxCustomerType\":\"A\",\"monthlySalesEstimate\":\"10\",\"quoteOptions\":[{\"id\":\"option-us\",\"country\":\"美国\",\"carrier\":\"承运商A\",\"channel\":\"渠道A\"},{\"id\":\"option-ca\",\"country\":\"加拿大\",\"carrier\":\"承运商B\",\"channel\":\"渠道B\"}],\"productSummary\":\"测试商品\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));

        var bundleBody = "{\"customerName\":\"组合测试客户\",\"quoteMode\":\"bundle\",\"primarySku\":\"SKU-1、SKU-2\",\"bundleItems\":[{\"sku\":\"SKU-1\",\"name\":\"商品一\",\"quantityPerSet\":2,\"effectiveWeightKg\":0.2,\"purchaseBaseUnitPriceCny\":11.32,\"purchaseInvoiceType\":\"普票6%\",\"purchaseInvoiceRatePercent\":6,\"purchaseInvoiceTaxApplied\":true,\"purchaseUnitPriceCny\":12,\"domesticFreightPerUnitCny\":1.5},{\"sku\":\"SKU-2\",\"name\":\"商品二\",\"quantityPerSet\":1,\"effectiveWeightKg\":0.35,\"purchaseUnitPriceCny\":20,\"domesticFreightPerUnitCny\":0}],\"productCategory\":\"保健品\",\"logisticsAttribute\":\"普货\",\"customerGrade\":\"S级客户\",\"taxCustomerType\":\"A\",\"monthlySalesEstimate\":\"10\",\"quoteOptions\":[{\"id\":\"bundle-us\",\"country\":\"美国\",\"carrier\":\"承运商A\",\"channel\":\"渠道A\"}],\"productSummary\":\"SKU-1 × 2 + SKU-2 × 1\"}";
        var bundleCreated = mvc.perform(post("/api/v1/quotations").session(session).with(csrf())
                        .header("Idempotency-Key", "quote-bundle-1").contentType("application/json").content(bundleBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.bundleItems.length()").value(2))
                .andExpect(jsonPath("$.data.bundleItems[0].effectiveWeightKg").value(0.2))
                .andExpect(jsonPath("$.data.bundleItems[0].purchaseInvoiceType").value("普票6%"))
                .andExpect(jsonPath("$.data.bundleItems[0].purchaseInvoiceRatePercent").value(6))
                .andExpect(jsonPath("$.data.bundleItems[0].purchaseInvoiceTaxApplied").value(true)).andReturn();
        var bundleId = mapper.readTree(bundleCreated.getResponse().getContentAsByteArray()).path("data").path("id").asText();
        mvc.perform(post("/api/v1/quotations").session(session).with(csrf())
                        .header("Idempotency-Key", "quote-bundle-1").contentType("application/json").content(bundleBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(bundleId));

        var other = new QuotationRecordEntity();
        other.id = UUID.randomUUID(); other.quoteNo = "Q-OTHER-1"; other.ownerAccount = "EMP001"; other.status = "pending";
        var otherPayload = JsonNodeFactory.instance.objectNode().put("id", other.id.toString()).put("no", other.quoteNo)
                .put("salespersonAccount", "EMP001").put("salespersonName", "其他员工").put("customerName", "其他客户")
                .put("status", "pending");
        otherPayload.putArray("revisions"); other.payload = otherPayload;
        other.createdAt = Instant.parse("2026-08-20T00:00:00Z"); other.updatedAt = other.createdAt;
        records.saveAndFlush(other);

        mvc.perform(get("/api/v1/quotations").session(session).param("scope", "mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + id + "')]").exists())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + bundleId + "')]").exists());
        mvc.perform(get("/api/v1/quotations").session(session).param("scope", "company"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + id + "')]").exists())
                .andExpect(jsonPath("$.data.items[?(@.id == '" + bundleId + "')].bundleItems.length()").value(2))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + other.id + "')]").exists());

        var updated = mvc.perform(patch("/api/v1/quotations/{id}", id).session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"_version\":" + version + ",\"status\":\"won\",\"dealLines\":[{\"id\":\"deal-us\",\"optionId\":\"option-us\",\"optionLabel\":\"美国 · 渠道A\",\"country\":\"美国\",\"carrier\":\"承运商A\",\"channel\":\"渠道A\",\"unitPriceUsd\":12.5,\"quantity\":2,\"amountUsd\":25},{\"id\":\"deal-ca\",\"optionId\":\"option-ca\",\"optionLabel\":\"加拿大 · 渠道B\",\"country\":\"加拿大\",\"carrier\":\"承运商B\",\"channel\":\"渠道B\",\"unitPriceUsd\":15,\"quantity\":3,\"amountUsd\":45}],\"actualQuoteUsd\":70,\"dealQuantity\":5,\"closedAt\":\"2026-08-25\",\"note\":\"多渠道成交\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("won"))
                .andExpect(jsonPath("$.data.dealLines.length()").value(2))
                .andExpect(jsonPath("$.data.actualQuoteUsd").value(70))
                .andReturn();
        var updatedVersion = mapper.readTree(updated.getResponse().getContentAsByteArray()).path("data").path("_version").asLong();
        mvc.perform(patch("/api/v1/quotations/{id}", id).session(session).with(csrf())
                        .contentType("application/json").content("{\"_version\":" + updatedVersion + ",\"status\":\"won\",\"note\":\"多渠道成交\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/quotations/{id}", id).session(session).with(csrf())
                        .contentType("application/json").content("{\"_version\":" + version + ",\"status\":\"lost\",\"note\":\"过期覆盖\"}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/v1/quotations/{id}", other.id).session(session).with(csrf())
                        .contentType("application/json").content("{\"_version\":0,\"status\":\"won\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/quotations/{id}/pdf", id).session(session)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/quotations/{id}/void", id).session(session).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/quotations/{id}/restore", id).session(session).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/quotations/{id}/shares", id).session(session).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/quotations/{id}/shares/{shareId}", id, UUID.randomUUID()).session(session).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/public/v1/quotation-shares/{token}", "a".repeat(40)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/public/v1/quotation-shares/{token}", "a".repeat(40)).session(session))
                .andExpect(status().isNotFound());

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
        mvc.perform(delete("/api/v1/quotation-drafts/mine").session(session).with(csrf())).andExpect(status().isOk());
        var draft = mvc.perform(put("/api/v1/quotation-drafts/mine/state").session(session).with(csrf())
                        .header("If-Match", "-1").contentType("application/json")
                        .content("{\"schemaVersion\":2,\"customerName\":\"未完成客户\",\"quoteMode\":\"single\",\"skuSearch\":\"SKU-1\",\"productCategory\":\"\",\"logisticsAttribute\":\"普货\",\"selectedCustomerGrade\":\"S\",\"selectedTaxCustomerType\":\"A\",\"monthlySalesEstimate\":\"10\",\"customQuoteQuantity\":5,\"quoteMatrixMode\":\"common\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.exists").value(true)).andReturn();
        var draftVersion = mapper.readTree(draft.getResponse().getContentAsByteArray()).path("data").path("version").asLong();
        mvc.perform(get("/api/v1/quotation-drafts/mine/state").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.payload.customerName").value("未完成客户"));
        mvc.perform(put("/api/v1/quotation-drafts/mine/state").session(session).with(csrf())
                        .header("If-Match", "-1").contentType("application/json")
                        .content("{\"schemaVersion\":2,\"customerName\":\"冲突客户\",\"quoteMode\":\"single\"}"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/quotation-drafts/mine/state").session(session).with(csrf()).header("If-Match", String.valueOf(draftVersion)))
                .andExpect(status().isOk());

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
