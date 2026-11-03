package com.milano.quotation.finance;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class WomensPricingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    ObjectNode policy() { return (ObjectNode)mapper.readTree("""
        {"schemaVersion":2,"basis":"reference-price-usd","referenceCoefficient":1.23,"enabled":true,"tiers":[
          {"fromUsd":0,"toUsd":20,"coefficient":1.27},{"fromUsd":20,"toUsd":30,"coefficient":1.25},
          {"fromUsd":30,"toUsd":50,"coefficient":1.23},{"fromUsd":50,"toUsd":null,"coefficient":1.22}]}
        """); }
    ObjectNode quote() {
        var q=(ObjectNode)mapper.readTree("""
          {"quoteMode":"womens-single","customerGrade":"女装自动系数","exchangeRate":7,"purchaseUnitPriceCny":130,"domesticFreightPerUnitCny":2,"customQuoteQuantity":1,
           "quoteOptions":[{"id":"o","quote1Usd":25,"quoteCustomUsd":25,"logisticsSamples":[{"quantity":1,"total":8}],
             "womensPricingSamples":[{"quantity":1,"costCny":140,"referenceQuoteCny":172.2,"fromUsd":20,"toUsd":30,"coefficient":1.25,"baseQuoteCny":175}]}]}
          """);
        q.set("womensPricing",policy().put("version",2));return q;
    }
    @Test void thresholdsUseUnroundedCostAndExchange() {
        var p=policy();WomensPricing.validatePolicy(p);
        assertEquals("1.27",WomensPricing.tier(p,new BigDecimal("99.999999"),new BigDecimal("6.15")).path("coefficient").asText());
        assertEquals("1.25",WomensPricing.tier(p,new BigDecimal("100"),new BigDecimal("6.15")).path("coefficient").asText());
        assertEquals("1.23",WomensPricing.tier(p,new BigDecimal("150"),new BigDecimal("6.15")).path("coefficient").asText());
        assertEquals("1.22",WomensPricing.tier(p,new BigDecimal("250"),new BigDecimal("6.15")).path("coefficient").asText());
    }
    @Test void acceptsCompleteSnapshotWithoutChangingIt() {
        var q=quote();var before=q.deepCopy();WomensPricing.validateSnapshot(q,policy(),2);assertEquals(before,q);
    }
    @Test void rejectsCoercedOrOverflowingSchemaVersions() {
        for (var value : new String[]{"\"1\"", "1.5", "4294967297", "null", "true"}) {
            var p=mapper.readTree(policy().toString().replace("\"schemaVersion\":2", "\"schemaVersion\":"+value));
            var error = assertThrows(com.milano.quotation.common.AppException.class,()->WomensPricing.validatePolicy(p),value);
            assertEquals(422,error.status().value());
        }
    }
    @Test void rejectsStalePolicyAndTamperedCostsCoefficients() {
        assertThrows(RuntimeException.class,()->WomensPricing.validateSnapshot(quote(),policy(),3));
        for(String field:new String[]{"costCny","referenceQuoteCny","coefficient","baseQuoteCny","fromUsd"}) {
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
    @Test void acceptsReferencePricedSingleAndBundleWithExtraQuantityColumns() throws Exception {
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
    @Test void referenceFactorIsConfigurableAndDoesNotCompoundOrIterateFinalPrice() {
        var p=policy();
        assertEquals("1.27",WomensPricing.tier(p,new BigDecimal("10"),BigDecimal.ONE).path("coefficient").asText());
        assertEquals("1.27",WomensPricing.tier(p,new BigDecimal("16"),BigDecimal.ONE).path("coefficient").asText());
        assertEquals("1.25",WomensPricing.tier(p,new BigDecimal("17"),BigDecimal.ONE).path("coefficient").asText());
        p.put("referenceCoefficient",1.5);assertEquals("1.25",WomensPricing.tier(p,new BigDecimal("15"),BigDecimal.ONE).path("coefficient").asText());
        for(var value:new String[]{"0","-1","11","1.1234567","null","\"1.23\""}) {
            var invalid=policy();invalid.set("referenceCoefficient",mapper.readTree(value));
            assertThrows(com.milano.quotation.common.AppException.class,()->WomensPricing.validatePolicy(invalid));
        }
        var missing=policy();missing.remove("referenceCoefficient");assertThrows(RuntimeException.class,()->WomensPricing.validatePolicy(missing));
        var tampered=quote();((ObjectNode)tampered.path("womensPricing")).put("referenceCoefficient",1.5);
        assertEquals(409,assertThrows(com.milano.quotation.common.AppException.class,()->WomensPricing.validateSnapshot(tampered,policy(),2)).status().value());
    }

}
