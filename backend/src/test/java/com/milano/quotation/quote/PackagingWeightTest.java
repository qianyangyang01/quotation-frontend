package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class PackagingWeightTest {
    static ObjectNode valid() {
        return (ObjectNode) JsonMapper.builder().build().readTree("""
          {"customerName":"包材验收","quoteMode":"single","primarySku":"A","productCategory":"日用品","logisticsAttribute":"普货","customerGrade":"A级客户","monthlySalesEstimate":"10",
           "systemQuoteUsd":6.35,"exchangeRate":6.7,
           "weightSnapshot":{"schemaVersion":1,"rule":"per-item-50g-1g-v1","specialPackagingScope":"per-shipment","specialPackagingGrams":10,
            "items":[{"sku":"A","quantityPerSet":1,"baseWeightKg":0.14,"standardPackagingWeightKg":0.003}],
            "quantities":[{"quantity":1,"baseWeightKg":0.14,"standardPackagingWeightKg":0.003,"specialPackagingWeightKg":0.01,"weightKg":0.153},
                          {"quantity":2,"baseWeightKg":0.28,"standardPackagingWeightKg":0.006,"specialPackagingWeightKg":0.01,"weightKg":0.296}]},
           "quoteOptions":[{"id":"us","country":"美国","carrier":"4PX","channel":"QC","quote1Usd":6.35,"quote2Usd":10,
            "logisticsInput":{"country":"美国","quantity":1,"baseWeightKg":0.14,"standardPackagingWeightKg":0.003,"specialPackagingWeightKg":0.01,"packagingWeightKg":0.013,"weightKg":0.153},
            "logisticsSamples":[{"quantity":2,"input":{"weightKg":0.296}}]}]}
          """);
    }
    @Test void validatesWithoutChangingTheSnapshotOrPricesAndAllowsLegacyAbsence() {
        var input=valid();var before=input.deepCopy(); PackagingWeight.record(input);assertEquals(before,input);
        input.remove("weightSnapshot");PackagingWeight.record(input);assertFalse(input.has("weightSnapshot"));
        assertEquals(6.35,input.path("systemQuoteUsd").asDouble());
    }
    @Test void rejectsRepeatedSpecialWeightOldOrdinaryRuleAndMismatchedLogistics() {
        for(var field:new String[]{"specialPackagingWeightKg","weightKg","standardPackagingWeightKg"}) {
            var input=valid();((ObjectNode)input.path("weightSnapshot").path("quantities").get(1)).put(field,.02);
            assertThrows(AppException.class,()->PackagingWeight.record(input));
        }
        var old=valid();((ObjectNode)old.path("weightSnapshot").path("items").get(0)).put("standardPackagingWeightKg",.006);
        assertThrows(AppException.class,()->PackagingWeight.record(old));
        var wrongSample=valid();((ObjectNode)wrongSample.path("quoteOptions").get(0).path("logisticsSamples").get(0).path("input")).put("weightKg",.306);
        assertThrows(AppException.class,()->PackagingWeight.record(wrongSample));
        var wrongParcel=valid();((ObjectNode)wrongParcel.path("quoteOptions").get(0).path("logisticsInput")).put("packagingWeightKg",.003);
        assertThrows(AppException.class,()->PackagingWeight.record(wrongParcel));
    }
    @Test void refusesNewCalculatedQuotesFromStaleTabsButDoesNotReinterpretHistory() {
        var input=valid();input.remove("weightSnapshot");
        assertDoesNotThrow(()->PackagingWeight.record(input));
        var error=assertThrows(AppException.class,()->new QuotationSubmissionValidator().validateQuotePricing(input));
        assertEquals(409,error.status().value());
        assertTrue(error.getMessage().contains("刷新报价页面"));
        assertEquals(.153,input.path("quoteOptions").get(0).path("logisticsInput").path("weightKg").asDouble());
        assertDoesNotThrow(()->new QuotationSubmissionValidator().validateQuotePricing(valid()));
    }
    @Test void validatesPhysicalItemsBeforeMultiplyingBundleSetsAtFractionalBoundary() {
        var input=valid();input.put("quoteMode","bundle");input.remove("quoteOptions");
        var w=(ObjectNode)input.path("weightSnapshot");
        w.putArray("items").addObject().put("sku","A").put("quantityPerSet",2).put("baseWeightKg",.050001).put("standardPackagingWeightKg",.002);
        var rows=w.putArray("quantities");
        rows.addObject().put("quantity",5).put("baseWeightKg",.50001).put("standardPackagingWeightKg",.02).put("specialPackagingWeightKg",.01).put("weightKg",.53001);
        assertDoesNotThrow(()->PackagingWeight.record(input));
        ((ObjectNode)rows.get(0)).put("specialPackagingWeightKg",.05).put("weightKg",.57001);
        assertThrows(AppException.class,()->PackagingWeight.record(input));
    }
}
