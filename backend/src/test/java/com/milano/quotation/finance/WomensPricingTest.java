package com.milano.quotation.finance;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class WomensPricingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    ObjectNode policy() { return (ObjectNode)mapper.readTree("""
        {"schemaVersion":1,"basis":"total-cost-usd","enabled":true,"tiers":[
          {"fromUsd":0,"toUsd":20,"coefficient":1.27},{"fromUsd":20,"toUsd":30,"coefficient":1.25},
          {"fromUsd":30,"toUsd":50,"coefficient":1.23},{"fromUsd":50,"toUsd":null,"coefficient":1.22}]}
        """); }
    ObjectNode quote() {
        var q=(ObjectNode)mapper.readTree("""
          {"quoteMode":"womens-single","customerGrade":"女装自动系数","exchangeRate":7,"purchaseUnitPriceCny":130,"domesticFreightPerUnitCny":2,"customQuoteQuantity":1,
           "quoteOptions":[{"id":"o","quote1Usd":25,"quoteCustomUsd":25,"logisticsSamples":[{"quantity":1,"total":8}],
             "womensPricingSamples":[{"quantity":1,"costCny":140,"fromUsd":20,"toUsd":30,"coefficient":1.25,"baseQuoteCny":175}]}]}
          """);
        q.set("womensPricing",policy().put("version",2));return q;
    }
    @Test void thresholdsUseUnroundedCostAndExchange() {
        var p=policy();WomensPricing.validatePolicy(p);
        assertEquals("1.27",WomensPricing.tier(p,new BigDecimal("139.999999"),new BigDecimal("7")).path("coefficient").asText());
        assertEquals("1.25",WomensPricing.tier(p,new BigDecimal("140"),new BigDecimal("7")).path("coefficient").asText());
        assertEquals("1.23",WomensPricing.tier(p,new BigDecimal("210"),new BigDecimal("7")).path("coefficient").asText());
        assertEquals("1.22",WomensPricing.tier(p,new BigDecimal("350"),new BigDecimal("7")).path("coefficient").asText());
    }
    @Test void acceptsCompleteSnapshotWithoutChangingIt() {
        var q=quote();var before=q.deepCopy();WomensPricing.validateSnapshot(q,policy(),2);assertEquals(before,q);
    }
    @Test void rejectsCoercedOrOverflowingSchemaVersions() {
        for (var value : new String[]{"\"1\"", "1.5", "4294967297", "null", "true"}) {
            var p=mapper.readTree(policy().toString().replace("\"schemaVersion\":1", "\"schemaVersion\":"+value));
            var error = assertThrows(com.milano.quotation.common.AppException.class,()->WomensPricing.validatePolicy(p),value);
            assertEquals(422,error.status().value());
        }
    }
    @Test void rejectsStalePolicyAndTamperedCostsCoefficients() {
        assertThrows(RuntimeException.class,()->WomensPricing.validateSnapshot(quote(),policy(),3));
        for(String field:new String[]{"costCny","coefficient","baseQuoteCny","fromUsd"}) {
            var q=quote();((ObjectNode)q.path("quoteOptions").get(0).path("womensPricingSamples").get(0)).put(field,999);
            assertThrows(RuntimeException.class,()->WomensPricing.validateSnapshot(q,policy(),2),field);
        }
    }
    @Test void requiresEveryPricedQuantityAndRejectsGradeMultiplication() {
        var q=quote();((ObjectNode)q.path("quoteOptions").get(0)).put("quote2Usd",48);
        assertThrows(RuntimeException.class,()->WomensPricing.validateSnapshot(q,policy(),2));
        var grade=quote();grade.put("customerGrade","S级客户");
        assertThrows(RuntimeException.class,()->WomensPricing.validateSnapshot(grade,policy(),2));
    }
    @Test void validatesBundleCostsAndIntervals() {
        var q=quote();q.put("quoteMode","womens-bundle");q.set("bundleItems",mapper.readTree("[{\"quantityPerSet\":2,\"purchaseUnitPriceCny\":65,\"domesticFreightPerUnitCny\":1}]"));
        WomensPricing.validateSnapshot(q,policy(),2);
        var p=policy();((ObjectNode)p.path("tiers").get(1)).put("fromUsd",19);
        assertThrows(RuntimeException.class,()->WomensPricing.validatePolicy(p));
        var disabled=policy().put("enabled",false);assertThrows(RuntimeException.class,()->WomensPricing.validateSnapshot(quote(),disabled,2));
    }
    @Test void acceptsBrowserGeneratedSingleAndBundleWithExtraQuantityColumns() throws Exception {
        for (var name : new String[]{"single","bundle"}) {
            ObjectNode q;
            try (var in=getClass().getResourceAsStream("/womens-pricing/"+name+".json")) {
                q=(ObjectNode)mapper.readTree(in);
            }
            var validator=new com.milano.quotation.quote.QuotationSubmissionValidator();
            validator.validate(q);validator.validateQuotePricing(q);
            WomensPricing.validateSnapshot(q,policy(),1);
            assertTrue(q.path("systemQuantityQuotes").path("quantities").toString().contains("4"));
            for(var option:q.path("quoteOptions")) assertTrue(option.path("womensPricingSamples").size()>=4);
        }
    }
}
