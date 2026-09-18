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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class QuotationWorkflowIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.milano.quotation.logistics.LogisticsQuotationGuard logisticsGuard;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    @Autowired QuotationRecordRepository records;
    @Autowired com.milano.quotation.security.UserAccountRepository users;
    @Autowired com.milano.quotation.security.UserAccountService userService;
    @MockitoBean QuotationReadinessService readiness;
    MockMvc mvc;

    @BeforeEach void setUp() { mvc = webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void channelPolicyUpdatesRequireFinanceCsrfAndFreshVersionAndRejectMalformedReplacement() throws Exception {
        var session=authenticatedSession();
        var body="""
            [{"category":"普货","enabled":true,"countryRules":[{"country":"美国","allowedChannels":["1::云途::YT-PH"]}]}]
            """;
        var url="/api/v1/finance-settings/channel-policies";
        mvc.perform(put(url).session(session).header("If-Match","-1").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put(url).with(csrf()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("sales").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_quote")))
                .header("If-Match","-1").contentType("application/json").content(body)).andExpect(status().isForbidden());
        var saved=mvc.perform(put(url).session(session).with(csrf()).header("If-Match","-1").contentType("application/json").content(body)).andExpect(status().isOk()).andReturn();
        var version=mapper.readTree(saved.getResponse().getContentAsByteArray()).path("data").path("_version").asLong();
        mvc.perform(put(url).session(session).with(csrf()).header("If-Match",version).contentType("application/json").content(body.replace("true","false"))).andExpect(status().isOk());
        mvc.perform(put(url).session(session).with(csrf()).header("If-Match",version).contentType("application/json").content(body)).andExpect(status().isConflict());
        mvc.perform(put(url).session(session).with(csrf()).header("If-Match",version+1).contentType("application/json").content(body.replace("[\"1::云途::YT-PH\"]","null"))).andExpect(status().isUnprocessableEntity());
        mvc.perform(get(url).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.value[0].enabled").value(false))
                .andExpect(jsonPath("$.data.value[0].countryRules[0].allowedChannels[0]").value("1::云途::YT-PH"));
    }

    @Test void customerOperationSettingsRequireFinanceAndProtectConcurrentVersions() throws Exception {
        var session = authenticatedSession();
        mvc.perform(get("/api/v1/finance-settings/tax-channels")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("sales").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_quote"))))
                .andExpect(status().isForbidden());
        var body = """
            {"customers":[{"id":"client-a","name":"客户甲","feeUsd":1.25,"enabled":true}]}
            """;
        var saved = mvc.perform(put("/api/v1/finance-settings/customer-operation-fees").session(session).with(csrf())
                .header("If-Match","-1").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.value.customers[0].feeUsd").value(1.25)).andReturn();
        var version = mapper.readTree(saved.getResponse().getContentAsByteArray()).path("data").path("_version").asLong();
        var draft = """
            {"schemaVersion":2,"customerName":"客户甲","selectedCustomerId":"client-a"}
            """;
        mvc.perform(put("/api/v1/quotation-drafts/mine/state").session(session).with(csrf()).header("If-Match","-1")
                .contentType("application/json").content(draft)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payload.selectedCustomerId").value("client-a"));
        for (var invalid : new String[]{draft.replace("\"client-a\"", "{}"), draft.replace("\"client-a\"", "\"data:secret\""),
                draft.replace("\"selectedCustomerId\":\"client-a\"", "\"product\":{\"customerId\":\"legacy\"}")}) {
            mvc.perform(put("/api/v1/quotation-drafts/mine/state").session(session).with(csrf()).header("If-Match","0")
                    .contentType("application/json").content(invalid)).andExpect(status().isUnprocessableEntity());
        }
        var quoteBody = """
            {"customerName":"客户甲","quoteMode":"single","primarySku":"SKU-1","productCategory":"其他","logisticsAttribute":"普货","customerGrade":"A级客户","monthlySalesEstimate":"10",
             "commissionThreshold":0.95,"systemQuoteUsd":6.35,"systemQuoteCny":42.55,"exchangeRate":6.7,"customerOperation":{"id":"client-a","name":"客户甲","feeUsd":1.25},"quoteOptions":[{"id":"us","country":"美国","carrier":"承运商A","channel":"渠道A"}]}
            """;
        var quotation = mvc.perform(post("/api/v1/quotations").session(session).with(csrf()).header("Idempotency-Key","customer-operation-snapshot")
                .contentType("application/json").content(quoteBody)).andExpect(status().isOk()).andReturn();
        var quotationId = mapper.readTree(quotation.getResponse().getContentAsByteArray()).path("data").path("id").asText();
        mvc.perform(get("/api/v1/quotations/{id}", quotationId).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.commissionThreshold").value(.95))
                .andExpect(jsonPath("$.data.systemQuoteUsd").value(6.35)).andExpect(jsonPath("$.data.systemQuoteCny").value(42.55));
        mvc.perform(get("/api/v1/finance-settings/customer-operation-fees")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("sales").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_quote"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.value.customers[0].name").value("客户甲"));
        mvc.perform(put("/api/v1/finance-settings/customer-operation-fees").with(csrf())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("sales").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_quote")))
                .header("If-Match",version).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/finance-settings/customer-operation-fees").session(session).with(csrf())
                .header("If-Match",version).contentType("application/json").content(body.replace("1.25","2.50"))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/finance-settings/customer-operation-fees").session(session).with(csrf())
                .header("If-Match",version).contentType("application/json").content(body)).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/finance-settings/customer-operation-fees").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value.customers[0].feeUsd").value(2.5));
        mvc.perform(get("/api/v1/quotations/"+quotationId).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerOperation.feeUsd").value(1.25));
    }

    @Test
    void keepsPersonalOutcomeEditingAndMakesCompanyRecordsReadOnly() throws Exception {
        var session = authenticatedSession();
        var taxBefore = mvc.perform(get("/api/v1/finance-settings/tax-settings").session(session)).andExpect(status().isOk()).andReturn();
        var surchargeBody = """
            {"countries":[{"country":"美国","fixedFeeUsd":2,"selected":true,"enabled":true}],"providers":[{"provider":"递四方","mode":"taxable","selected":true}]}
            """;
        mvc.perform(put("/api/v1/finance-settings/surcharge-settings").session(session).with(csrf()).header("If-Match", "-1").contentType("application/json").content(surchargeBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.value.countries[0].fixedFeeUsd").value(2));
        mvc.perform(put("/api/v1/finance-settings/surcharge-settings").session(session).with(csrf()).header("If-Match", "-1").contentType("application/json").content(surchargeBody))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/finance-settings").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data['surcharge-settings'].value.providers[0].mode").value("taxable"));
        var taxAfter = mvc.perform(get("/api/v1/finance-settings/tax-settings").session(session)).andExpect(status().isOk()).andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(mapper.readTree(taxBefore.getResponse().getContentAsByteArray()).path("data"), mapper.readTree(taxAfter.getResponse().getContentAsByteArray()).path("data"));
        mvc.perform(post("/api/v1/quotations").session(session).with(csrf())
                        .header("Idempotency-Key", "quote-invalid-1").contentType("application/json").content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'customerName')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'monthlySalesEstimate')]").exists());
        var created = mvc.perform(post("/api/v1/quotations").session(session).with(csrf())
                        .header("Idempotency-Key", "quote-test-1")
                        .contentType("application/json")
                        .content("{\"customerId\":\"11111111-1111-1111-1111-111111111111\",\"customerName\":\"测试客户\",\"quoteMode\":\"single\",\"primarySku\":\"SKU-1\",\"productCategory\":\"日用品\",\"logisticsAttribute\":\"普货\",\"customerGrade\":\"A级客户\",\"monthlySalesEstimate\":\"10\",\"quoteOptions\":[{\"id\":\"option-us\",\"country\":\"美国\",\"carrier\":\"承运商A\",\"channel\":\"渠道A\"},{\"id\":\"option-ca\",\"country\":\"加拿大\",\"carrier\":\"承运商B\",\"channel\":\"渠道B\"}],\"productSummary\":\"测试商品\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.customerId").doesNotExist()).andReturn();
        var createdData = mapper.readTree(created.getResponse().getContentAsByteArray()).path("data");
        var id = createdData.path("id").asText(); var version = createdData.path("_version").asLong();

        mvc.perform(post("/api/v1/quotations").session(session).with(csrf())
                        .header("Idempotency-Key", "quote-test-1")
                        .contentType("application/json")
                        .content("{\"customerId\":\"11111111-1111-1111-1111-111111111111\",\"customerName\":\"测试客户\",\"quoteMode\":\"single\",\"primarySku\":\"SKU-1\",\"productCategory\":\"日用品\",\"logisticsAttribute\":\"普货\",\"customerGrade\":\"A级客户\",\"monthlySalesEstimate\":\"10\",\"quoteOptions\":[{\"id\":\"option-us\",\"country\":\"美国\",\"carrier\":\"承运商A\",\"channel\":\"渠道A\"},{\"id\":\"option-ca\",\"country\":\"加拿大\",\"carrier\":\"承运商B\",\"channel\":\"渠道B\"}],\"productSummary\":\"测试商品\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));

        var bundleBody = "{\"customerName\":\"组合测试客户\",\"quoteMode\":\"bundle\",\"primarySku\":\"SKU-1、SKU-2\",\"bundleItems\":[{\"sku\":\"SKU-1\",\"name\":\"商品一\",\"quantityPerSet\":2,\"effectiveWeightKg\":0.2,\"purchaseBaseUnitPriceCny\":11.32,\"purchaseInvoiceType\":\"普票6%\",\"purchaseInvoiceRatePercent\":6,\"purchaseInvoiceTaxApplied\":true,\"purchaseUnitPriceCny\":12,\"domesticFreightPerUnitCny\":1.5},{\"sku\":\"SKU-2\",\"name\":\"商品二\",\"quantityPerSet\":1,\"effectiveWeightKg\":0.35,\"purchaseUnitPriceCny\":20,\"domesticFreightPerUnitCny\":0}],\"productCategory\":\"保健品\",\"logisticsAttribute\":\"普货\",\"customerGrade\":\"S级客户\",\"monthlySalesEstimate\":\"10\",\"quoteOptions\":[{\"id\":\"bundle-us\",\"country\":\"美国\",\"carrier\":\"承运商A\",\"channel\":\"渠道A\"}],\"productSummary\":\"SKU-1 × 2 + SKU-2 × 1\"}";
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

        mvc.perform(get("/api/v1/suppliers").session(session))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/migration-jobs/business/preview").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test void retainsUnavailableSnapshotAndRejectsBothDealWritePaths() throws Exception {
        var session = authenticatedSession();
        var payload = mapper.createObjectNode().put("customerName","验收混合报价").put("quoteMode","single").put("primarySku","SKU-1").put("productCategory","日用品").put("logisticsAttribute","普货").put("customerGrade","A级客户").put("monthlySalesEstimate","10");
        var options = payload.putArray("quoteOptions");
        options.addObject().put("id","good").put("country","美国").put("carrier","A").put("channel","可用").put("quote1Usd",10);
        var missing=options.addObject().put("id","missing").put("country","美国").put("carrier","B").put("channel","超重").put("available",false).put("availabilityMessage","超过5kg上限");
        for(var field:java.util.List.of("quote1Usd","quote2Usd","quote3Usd","quoteCustomUsd"))missing.putNull(field);
        var response=mvc.perform(post("/api/v1/quotations").session(session).with(csrf()).header("Idempotency-Key","acceptance-"+UUID.randomUUID()).contentType("application/json").content(mapper.writeValueAsString(payload))).andExpect(status().isOk()).andReturn();
        var saved=mapper.readTree(response.getResponse().getContentAsByteArray()).path("data");
        var id=saved.path("id").asText();var version=saved.path("_version").asLong();
        org.junit.jupiter.api.Assertions.assertTrue(saved.path("quoteOptions").get(1).path("quote1Usd").isNull());
        org.junit.jupiter.api.Assertions.assertEquals("超过5kg上限",saved.path("quoteOptions").get(1).path("availabilityMessage").asText());
        var patchBody=mapper.createObjectNode().put("_version",version).put("status","won").put("dealOptionId","missing").put("actualQuoteUsd",10).put("dealQuantity",1);
        mvc.perform(patch("/api/v1/quotations/{id}",id).session(session).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(patchBody))).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value("不可用报价方案不能回填成交"));
        patchBody.remove("dealOptionId");
        patchBody.putArray("dealLines").addObject().put("id","d").put("optionId","missing").put("unitPriceUsd",10).put("quantity",1).put("amountUsd",10);
        mvc.perform(patch("/api/v1/quotations/{id}",id).session(session).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(patchBody))).andExpect(status().isUnprocessableEntity());
    }
    @Test void customerPriceSnapshotsSurviveReadbackRejectStaleWritesAndPreserveOwnerPermissions() throws Exception {
        var session=authenticatedSession();
        var body="""
          {"customerName":"客户改价验收","quoteMode":"single","primarySku":"SKU-1","productCategory":"日用品","logisticsAttribute":"普货","customerGrade":"A级客户","monthlySalesEstimate":"10","customQuoteQuantity":4,
           "quoteOptions":[{"id":"yanwen-a","country":"US","carrier":"Yanwen","channel":"A","quote1Usd":2,"quote2Usd":3,"quoteCustomUsd":5}],
           "customerQuote":{"quantities":[1,2,4],"rows":[{"optionId":"yanwen-a","prices":[1.8,2.7,4.6]}]}}
          """;
        var result=mvc.perform(post("/api/v1/quotations").session(session).with(csrf()).header("Idempotency-Key","customer-prices")
          .contentType("application/json").content(body)).andExpect(status().isOk())
          .andExpect(jsonPath("$.data.customerQuote.rows[0].prices[1]").value(2.7))
          .andExpect(jsonPath("$.data.systemQuantityQuotes.rows[0].prices[1]").value(3)).andReturn();
        var created=mapper.readTree(result.getResponse().getContentAsByteArray()).path("data");var id=created.path("id").asText();var version=created.path("_version").asLong();
        var patchBody=mapper.createObjectNode().put("_version",version);
        patchBody.set("customerQuote",mapper.readTree("{\"quantities\":[1,2,4],\"rows\":[{\"optionId\":\"yanwen-a\",\"prices\":[1.8,2.6,4.6]}]}"));
        var updated=mvc.perform(patch("/api/v1/quotations/{id}",id).session(session).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(patchBody)))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("pending"))
          .andExpect(jsonPath("$.data.sheetQuote.rows[0].prices[1]").value(2.7))
          .andExpect(jsonPath("$.data.customerQuote.rows[0].prices[1]").value(2.6))
          .andExpect(jsonPath("$.data.revisions[0].field").value("customerQuote")).andReturn();
        mvc.perform(patch("/api/v1/quotations/{id}",id).session(session).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(patchBody))).andExpect(status().isConflict());
        var latest=mapper.readTree(updated.getResponse().getContentAsByteArray()).path("data");
        org.junit.jupiter.api.Assertions.assertEquals(latest.path("_version").asLong(),records.findById(UUID.fromString(id)).orElseThrow().version,"returned version must match the committed row");
        patchBody.put("_version",latest.path("_version").asLong());patchBody.put("systemQuoteUsd",1);
        mvc.perform(patch("/api/v1/quotations/{id}",id).session(session).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(patchBody))).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/quotations/{id}",id).session(session)).andExpect(status().isOk())
          .andExpect(jsonPath("$.data.systemQuantityQuotes.rows[0].prices[1]").value(3))
          .andExpect(jsonPath("$.data.customerQuote.rows[0].prices[1]").value(2.6));
        patchBody.remove("systemQuoteUsd");
        patchBody.set("customerQuote",mapper.readTree("{\"quantities\":[1,2,4],\"rows\":[{\"optionId\":\"yanwen-a\",\"prices\":[2,2.6,4.6]}]}"));
        var resaved=mvc.perform(patch("/api/v1/quotations/{id}",id).session(session).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(patchBody)))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.customerQuote.rows[0].prices[0]").value(2))
          .andExpect(jsonPath("$.data.sheetQuote.rows[0].prices[0]").value(1.8)).andReturn();
        var resavedPayload=mapper.readTree(resaved.getResponse().getContentAsByteArray()).path("data");
        org.junit.jupiter.api.Assertions.assertEquals(resavedPayload.path("_version").asLong(),records.findById(UUID.fromString(id)).orElseThrow().version);
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        var concurrentVersion=resavedPayload.path("_version").asLong();
        var concurrentWrites=java.util.stream.IntStream.range(0,2).mapToObj(index->java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try {
                var competing=patchBody.deepCopy();competing.put("_version",concurrentVersion);
                var prices=(tools.jackson.databind.node.ArrayNode)competing.path("customerQuote").path("rows").get(0).path("prices");
                prices.set(1,mapper.valueToTree(index==0?2.5:2.4));
                barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);
                return mvc.perform(patch("/api/v1/quotations/{id}",id).session(session).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(competing))).andReturn().getResponse().getStatus();
            } catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        })).toList();
        var statuses=new java.util.ArrayList<Integer>();
        for(var write:concurrentWrites)statuses.add(write.get(20,java.util.concurrent.TimeUnit.SECONDS));
        java.util.Collections.sort(statuses);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(200,409),statuses,"exactly one concurrent customer-price update must commit");
        var concurrentPayload=records.findById(UUID.fromString(id)).orElseThrow().payload;
        org.junit.jupiter.api.Assertions.assertEquals(3,concurrentPayload.path("systemQuantityQuotes").path("rows").get(0).path("prices").get(1).asInt());
        var entity=records.findById(UUID.fromString(id)).orElseThrow();entity.ownerAccount="ANOTHER-OWNER";records.saveAndFlush(entity);
        patchBody.remove("systemQuoteUsd");
        mvc.perform(patch("/api/v1/quotations/{id}",id).session(session).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(patchBody))).andExpect(status().isForbidden());
    }
    @Test void confirmsPricesAndInvalidatesOnEditWithoutChangingDealStatus() throws Exception {
        var session=authenticatedSession();
        var row=new QuotationRecordEntity();row.id=UUID.randomUUID();row.quoteNo="CONFIRM-TEST";row.ownerAccount="ADMIN";row.status="won";
        row.createdAt=Instant.now();row.updatedAt=row.createdAt;
        row.payload=mapper.readTree("""
          {"id":"confirm-test","status":"won","actualQuoteUsd":12,"quoteOptions":[{"id":"a","quote1Usd":2}],"customerQuote":{"quantities":[1],"rows":[{"optionId":"a","prices":[1.8]}]}}
          """);records.saveAndFlush(row);
        var confirm=mapper.createObjectNode().put("_version",row.version).put("quoteConfirmed",true);
        var response=mvc.perform(patch("/api/v1/quotations/{id}",row.id).session(session).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(confirm)))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("won"))
          .andExpect(jsonPath("$.data.quoteConfirmed").value(true)).andExpect(jsonPath("$.data.quoteConfirmedAt").isNotEmpty())
          .andExpect(jsonPath("$.data.quoteConfirmedBy").isNotEmpty()).andReturn();
        var payload=mapper.readTree(response.getResponse().getContentAsByteArray()).path("data");
        mvc.perform(patch("/api/v1/quotations/{id}",row.id).session(session).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(confirm))).andExpect(status().isConflict());
        var edit=mapper.createObjectNode().put("_version",payload.path("_version").asLong());
        edit.set("customerQuote",mapper.readTree("""
          {"quantities":[1],"rows":[{"optionId":"a","prices":[1.7]}]}
          """));
        mvc.perform(patch("/api/v1/quotations/{id}",row.id).session(session).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(edit)))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("won"))
          .andExpect(jsonPath("$.data.actualQuoteUsd").value(12)).andExpect(jsonPath("$.data.quoteConfirmed").value(false))
          .andExpect(jsonPath("$.data.quoteConfirmedAt").doesNotExist()).andExpect(jsonPath("$.data.revisions[0].field").value("quoteConfirmed"));
        mvc.perform(get("/api/v1/quotations/{id}",row.id).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.quoteConfirmed").value(false));
        var currentVersion=records.findById(row.id).orElseThrow().version;
        var barrier=new java.util.concurrent.CyclicBarrier(12);
        try (var executor=java.util.concurrent.Executors.newFixedThreadPool(12)) {
        var calls=java.util.stream.IntStream.range(0,12).mapToObj(index->java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try {
                var competing=index==0 ? confirm.deepCopy() : edit.deepCopy();competing.put("_version",currentVersion);
                if(index!=0) ((tools.jackson.databind.node.ArrayNode)competing.path("customerQuote").path("rows").get(0).path("prices")).set(0,mapper.valueToTree(1.6));
                barrier.await(10,java.util.concurrent.TimeUnit.SECONDS);
                return mvc.perform(patch("/api/v1/quotations/{id}",row.id).session(session).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(competing))).andReturn().getResponse().getStatus();
            } catch(Exception e) { throw new java.util.concurrent.CompletionException(e); }
        },executor)).toList();
        var statuses=new java.util.ArrayList<Integer>();for(var call:calls)statuses.add(call.get(20,java.util.concurrent.TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertEquals(1,java.util.Collections.frequency(statuses,200));
        org.junit.jupiter.api.Assertions.assertEquals(11,java.util.Collections.frequency(statuses,409));
        var finalPayload=records.findById(row.id).orElseThrow().payload;
        org.junit.jupiter.api.Assertions.assertEquals("won",finalPayload.path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(finalPayload.path("quoteConfirmed").asBoolean()?1.7:1.6,finalPayload.path("customerQuote").path("rows").get(0).path("prices").get(0).asDouble());
        }
    }
    @Test void packagingSnapshotsPersistForBothRolesDraftsRemainIsolatedAndForbiddenRolesCannotWrite() throws Exception {
        var auths=new java.util.ArrayList<org.springframework.test.web.servlet.request.RequestPostProcessor>();
        for(var role:new String[]{"employee","super_admin"}) {
            var account="PACK_"+role.toUpperCase();
            users.saveAndFlush(com.milano.quotation.security.UserAccount.create(account,role,"hash",role,false));
            var principal=userService.loadUserByUsername(account);
            auths.add(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(principal));
        }
        for(int i=0;i<auths.size();i++) {
            var login=auths.get(i);
            var draft=mapper.createObjectNode().put("schemaVersion",2).put("specialPackagingGrams",10+i);
            var saved=mvc.perform(put("/api/v1/quotation-drafts/mine/state").with(login).with(csrf()).header("If-Match",-1).contentType("application/json").content(mapper.writeValueAsString(draft)))
                .andExpect(status().isOk()).andReturn();
            var version=mapper.readTree(saved.getResponse().getContentAsByteArray()).path("data").path("version").asLong();
            var changed=draft.deepCopy().put("specialPackagingGrams",20+i);
            mvc.perform(put("/api/v1/quotation-drafts/mine/state").with(login).with(csrf()).header("If-Match",version).contentType("application/json").content(mapper.writeValueAsString(changed))).andExpect(status().isOk());
            mvc.perform(put("/api/v1/quotation-drafts/mine/state").with(login).with(csrf()).header("If-Match",version).contentType("application/json").content(mapper.writeValueAsString(draft))).andExpect(status().isConflict());
            var created=mvc.perform(post("/api/v1/quotations").with(login).with(csrf()).header("Idempotency-Key","packaging-"+i).contentType("application/json").content(mapper.writeValueAsString(PackagingWeightTest.valid())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.weightSnapshot.quantities[1].weightKg").value(.296)).andReturn();
            var record=mapper.readTree(created.getResponse().getContentAsByteArray()).path("data");
            mvc.perform(get("/api/v1/quotations/{id}",record.path("id").asText()).with(login))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.weightSnapshot.specialPackagingGrams").value(10)).andExpect(jsonPath("$.data.systemQuoteUsd").value(6.35));
            var forged=mapper.createObjectNode().put("_version",record.path("_version").asLong());forged.set("weightSnapshot",PackagingWeightTest.valid().path("weightSnapshot"));
            mvc.perform(patch("/api/v1/quotations/{id}",record.path("id").asText()).with(login).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(forged))).andExpect(status().isUnprocessableEntity());
        }
        for(int i=0;i<auths.size();i++) mvc.perform(get("/api/v1/quotation-drafts/mine/state").with(auths.get(i))).andExpect(status().isOk()).andExpect(jsonPath("$.data.payload.specialPackagingGrams").value(20+i));
        users.saveAndFlush(com.milano.quotation.security.UserAccount.create("PACK_DENIED","无报价权限","hash","purchase",false));
        mvc.perform(post("/api/v1/quotations").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(userService.loadUserByUsername("PACK_DENIED"))).with(csrf()).header("Idempotency-Key","denied").contentType("application/json").content(mapper.writeValueAsString(PackagingWeightTest.valid()))).andExpect(status().isForbidden());
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
