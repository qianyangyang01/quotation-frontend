package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FobLegacyInvoiceTest {
    static ObjectNode raw(){return FobQuotation.object().put("dataSource","fob").put("priceRaw","原文保持");}
    static ObjectNode product(String kind){return FobPricingTest.product(kind).put("source","fob");}
    @Test void bothSourcesDefaultToTenPointsWithoutChangingProcurement(){
        for(var source:List.of("fob","standard"))for(var kind:List.of("rebate-fob","rebate-cfr-cif")){
            var r=raw();var before=r.deepCopy();var p=product(kind).put("source",source);FobPricing.applyPurchase(p,r);
            assertEquals(before,r);assertEquals("default-tax-point",p.at("/invoicePricing/mode").asText());assertTrue(p.at("/invoicePricing/verificationRequired").asBoolean());
            assertEquals(2,p.at("/invoicePricing/missingFields").size());
            assertEquals(0,FobPricing.purchaseCost(p,new BigDecimal("45")).compareTo(new BigDecimal("49.50")));
            var rows=FobPricing.pricing(p,new BigDecimal("7.2"),FobPricingTest.config());
            assertEquals(kind.equals("rebate-fob")?"3.72":"3.82",rows.at("/rows/0/declaredUsd").asText());assertEquals(4,rows.path("policyVersion").asInt());
        }
    }
    @Test void missingFieldsNeverBypassExplicitBadOrConflictingProcurement(){
        for(var invoice:List.of("普票","无票","非专票","专票13，满1000开"))assertThrows(RuntimeException.class,()->FobPricing.applyPurchase(product("rebate-fob"),raw().put("invoiceType",invoice)));
        for(var value:List.of("-1","1.01","13"))assertThrows(RuntimeException.class,()->FobPricing.applyPurchase(product("rebate-fob"),raw().put("taxPoint",new BigDecimal(value))));
        for(var value:List.of("0","-1","0.13","60"))assertThrows(RuntimeException.class,()->FobPricing.applyPurchase(product("rebate-fob"),raw().put("taxIncludedPriceCny",new BigDecimal(value))));
        var p=product("rebate-fob");((ObjectNode)p.at("/parsed/priceTiers/0")).put("unitPriceCny",0);assertThrows(RuntimeException.class,()->FobPricing.applyPurchase(p,raw()));
    }
    @Test void onlyBothFieldsRemoveReminderAndUnrelatedEditsCannotDismissIt(){
        var r=raw();r.put("category","采购只改了名称");var p=product("rebate-fob");FobPricing.applyPurchase(p,r);assertTrue(p.at("/invoicePricing/verificationRequired").asBoolean());
        r.put("invoiceType","专票13");FobPricing.applyPurchase(p,r);assertTrue(p.at("/invoicePricing/verificationRequired").asBoolean());assertEquals("票点",p.at("/invoicePricing/missingFields/0").asText());
        r.put("taxPoint",new BigDecimal("0.13"));var before=r.deepCopy();FobPricing.applyPurchase(p,r);assertEquals(before,r);assertFalse(p.at("/invoicePricing/verificationRequired").asBoolean());assertEquals("actual-tax-point",p.at("/invoicePricing/mode").asText());
        assertEquals(0,FobPricing.purchaseCost(p,new BigDecimal("45")).compareTo(new BigDecimal("50.85")));
        r.remove("invoiceType");FobPricing.applyPurchase(p,r);assertTrue(p.at("/invoicePricing/verificationRequired").asBoolean());assertEquals(0.1,p.at("/invoicePricing/effectiveTaxPoint").asDouble());
    }
    @Test void noReverseRatioAndNoDoubleTaxForIncludedTiers(){
        var r=FobInvoiceRatioTest.ready();var p=FobInvoiceRatioTest.product(r,"rebate-fob").put("source","fob");FobPricing.applyPurchase(p,r);assertEquals("actual-tax-point",p.at("/invoicePricing/mode").asText());
        var included=raw().put("purchasePriceBasis","tax_included");p=product("rebate-fob");FobPricing.applyPurchase(p,included);assertTrue(p.at("/invoicePricing/verificationRequired").asBoolean());assertEquals("included",p.at("/invoicePricing/mode").asText());assertEquals(new BigDecimal("45"),FobPricing.purchaseCost(p,new BigDecimal("45")));
    }
    @Test void ordinaryQuotationOnlyChangesFinalFactorAndDoesNotRequireInvoiceVerification(){
        for(var invoice:List.of("","普票","无票","专票13")){
            var p=product("ordinary-fob");FobPricing.applyPurchase(p,raw().put("invoiceType",invoice).put("taxPoint",new BigDecimal("0.13")));
            assertEquals("ordinary-fixed-ticket",p.at("/invoicePricing/mode").asText());assertEquals(0,FobPricing.purchaseCost(p,new BigDecimal("45")).compareTo(new BigDecimal("49.50")));assertFalse(p.at("/invoicePricing/verificationRequired").asBoolean());
            var rows=FobPricing.pricing(p,new BigDecimal("7.2"),FobPricingTest.config());assertEquals("4.13",rows.at("/rows/0/declaredUsd").asText());assertEquals(new BigDecimal("1.20"),rows.path("fobCoefficient").decimalValue());
        }
    }
    @Test void displayLabelAndAssumedTicketRateSurviveRedactionButFactorDoesNot(){
        var p=product("rebate-fob");FobPricing.applyPurchase(p,raw());p.set("pricing",FobPricing.pricing(p,new BigDecimal("7.2"),FobPricingTest.config()));FobCoefficientVisibility.redact(p);
        assertFalse(p.toString().contains("fobCoefficient"));assertFalse(p.toString().contains("fobInvoiceRatio"));assertEquals(0.1,p.at("/invoicePricing/effectiveTaxPoint").asDouble());assertTrue(p.at("/invoicePricing/verificationRequired").asBoolean());
    }
    @Test void zeroTicketPointIsExplicitAndNotMistakenForMissing(){
        var r=raw().put("invoiceType","专票").put("taxPoint",0).put("taxIncludedPriceCny",22);var p=product("rebate-fob");FobPricing.applyPurchase(p,r);assertFalse(p.at("/invoicePricing/verificationRequired").asBoolean());assertEquals(new BigDecimal("45"),FobPricing.purchaseCost(p,new BigDecimal("45")));
    }
}
