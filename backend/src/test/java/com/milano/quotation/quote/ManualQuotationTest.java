package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ManualQuotationTest {
    private final JsonMapper mapper = new JsonMapper();
    ObjectNode quote(String mode) {
        var root = mapper.createObjectNode().put("quoteMode",mode).put("primarySku", "").put("productCategory", "")
            .put("customerName", "Manual QA").put("customerGrade", "S级客户").put("logisticsAttribute", "普货").put("customQuoteQuantity",3);
        var cost = "shipping-only".equals(mode) ? 0 : 30;
        root.putObject("manualPricing").put("costCny",cost).put("weightGrams",500);
        root.putObject("financeVersions").put("exchange-rate",1);
        root.putObject("purchaseVersions");
        var option = root.putArray("quoteOptions").addObject().put("freightCny",30).put("totalCostCny",cost*3+80);
        option.putObject("logisticsInput").put("quantity",1).put("weightKg",.5).put("baseWeightKg",.5)
            .put("packagingWeightKg",0).put("standardPackagingWeightKg",0).put("specialPackagingWeightKg",0);
        var samples = option.putArray("logisticsSamples");
        for (int n : List.of(1,2,3,7)) samples.addObject().put("quantity",n).put("total",n*25+5).putObject("input").put("weightKg",n*.5);
        root.putObject("systemQuantityQuotes").putArray("quantities").add(1).add(7);
        return root;
    }
    @Test void bothManualModesPassSubmissionWithoutPurchaseSkuOrPackagingSnapshot() {
        for (var mode : List.of("freight-trial", "shipping-only")) {
            var root = quote(mode);
            var validator = new QuotationSubmissionValidator();
            assertDoesNotThrow(() -> validator.validate(root));
            assertDoesNotThrow(() -> validator.validateQuotePricing(root));
            assertFalse(root.has("weightSnapshot"));
        }
    }
    @Test void rejectsMissingNegativeNonFiniteAndExcessPrecisionInputs() {
        for (var value : List.of("null", "-1", "\"NaN\"", "\"500\"", "1000001", "0.0001")) {
            var root = quote("freight-trial");
            ((ObjectNode)root.path("manualPricing")).set("weightGrams",mapper.readTree(value));
            assertThrows(AppException.class, () -> ManualQuotation.validate(root), value);
        }
        var root = quote("freight-trial"); ((ObjectNode)root.path("manualPricing")).put("costCny",.001);
        assertThrows(AppException.class, () -> ManualQuotation.validate(root));
    }
    @Test void rejectsProductCostForShippingOnlyAndRepeatedProcurementCosts() {
        var root=quote("shipping-only"); ((ObjectNode)root.path("manualPricing")).put("costCny",30);
        assertThrows(AppException.class, () -> ManualQuotation.validate(root));
        for(var field:List.of("purchaseUnitPriceCny","purchaseBaseUnitPriceCny","domesticFreightPerUnitCny","purchaseInvoiceRatePercent")) {
            var trial=quote("freight-trial");trial.put(field,1);
            assertThrows(AppException.class, () -> ManualQuotation.validate(trial));
        }
    }
    @Test void rejectsInjectedPackagingAndIncorrectQuantityWeightOrTotalCost() {
        for (var field : List.of("weightKg","baseWeightKg","packagingWeightKg","standardPackagingWeightKg","specialPackagingWeightKg")) {
            var root=quote("freight-trial");((ObjectNode)root.path("quoteOptions").get(0).path("logisticsInput")).put(field,.51);
            assertThrows(AppException.class, () -> ManualQuotation.validate(root),field);
        }
        var root=quote("freight-trial");((ObjectNode)root.path("quoteOptions").get(0)).put("totalCostCny",61);
        assertThrows(AppException.class, () -> ManualQuotation.validate(root));
        var wrongSample=quote("freight-trial");((ObjectNode)wrongSample.path("quoteOptions").get(0).path("logisticsSamples").get(1).path("input")).put("weightKg",1.02);
        assertThrows(AppException.class, () -> ManualQuotation.validate(wrongSample));
    }
    @Test void requiresAllQuantityColumnsAndPreventsManualModeFromBypassingSkuValidation() {
        var root=quote("freight-trial"); root.withObject("/systemQuantityQuotes").withArray("/quantities").add(10);
        assertThrows(AppException.class, () -> ManualQuotation.validate(root));
        var sku=quote("single"); assertThrows(AppException.class, () -> ManualQuotation.validate(sku));
        var attached=quote("freight-trial");attached.put("primarySku","REAL-SKU");
        assertThrows(AppException.class, () -> ManualQuotation.validate(attached));
    }
    @Test void rejectsMissingAdoptedQuantitySampleWithoutServerError() {
        var root=quote("freight-trial");
        ((ObjectNode)root.path("quoteOptions").get(0).path("logisticsInput"))
            .put("quantity",4).put("weightKg",2).put("baseWeightKg",2);
        assertThrows(AppException.class, () -> ManualQuotation.validate(root));
    }
    @Test void acceptsZeroCostAndPreservesInputSnapshotWithoutMutation() {
        var root=quote("freight-trial");((ObjectNode)root.path("manualPricing")).put("costCny",0);
        ((ObjectNode)root.path("quoteOptions").get(0)).put("totalCostCny",80);
        var before=root.deepCopy();ManualQuotation.validate(root);assertEquals(before,root);
    }
}
