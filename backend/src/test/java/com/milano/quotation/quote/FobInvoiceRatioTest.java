package com.milano.quotation.quote;

import com.milano.quotation.fob.FobInvoicePolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FobInvoiceRatioTest {
    static ObjectNode raw(){return FobQuotation.object().put("dataSource","standard").put("invoiceType","专票13").put("purchasePriceBasis","quoted")
        .put("minOrderQty",1).put("purchasePriceCny",50).put("taxIncludedPriceCny",60).put("taxPoint",new BigDecimal("0.2"))
        .put("tier2MinQty",100).put("tier2PriceCny",45).put("fobInvoiceRatioConfirmed",true).put("invoiceVerificationNote","供应商已确认全部阶梯按同一比例含专票");}
    static ObjectNode confirmed(ObjectNode raw){raw.remove("fobInvoiceConfirmedFingerprint");FobInvoicePolicy.maintainConfirmation(raw,null,raw);return raw;}
    static ObjectNode product(ObjectNode raw,String kind){
        var p=FobQuotation.object().put("classification",kind);p.set("parsed",FobQuotation.standard(raw.put("catalogState","ready"),true));return p;
    }
    static ObjectNode ready(){var r=raw().put("freeShipping","是");return confirmed(r);}
    static ObjectNode policy(){var p=FobQuotation.object().put("version",1);p.set("value",FobPricing.defaults());return p;}
    @Test void usesActualPointForSelectedTierAndEachClassificationWithoutAddingTaxTwice(){
        for(var pair:List.of(new String[]{"rebate-fob","8.10"},new String[]{"rebate-cfr-cif","8.33"},new String[]{"ordinary-fob","8.25"})){
            var r=ready();var p=product(r,pair[0]);FobPricing.applyPurchase(p,r);
            var result=FobPricing.pricing(p,new BigDecimal("7.2"),policy());
            assertEquals(pair[1],result.at("/rows/1/declaredUsd").asText());
            assertEquals(0,result.at("/rows/1/purchaseCny").decimalValue().compareTo(BigDecimal.valueOf(45)));
            assertEquals(0,result.at("/rows/1/taxIncludedCny").decimalValue().compareTo(new BigDecimal(pair[0].equals("ordinary-fob")?"49.5":"54")));
            assertEquals(50,p.at("/parsed/priceTiers/0/unitPriceCny").asInt());
        }
    }
    @Test void onlyUnambiguousSpecialInvoiceNamesAreAccepted(){
        for(var text:List.of("专票13","专票13%","专票１０％","专票3个点"," 专票 13 ","增值税专用发票","专票","专票6"))assertTrue(FobInvoicePolicy.special(text),text);
        for(var text:List.of("非专票","专票待核实","普票/专票13","专票13，满1000开","13个点","专票13但不能开","普票","未确认"))assertFalse(FobInvoicePolicy.special(text),text);
    }
    @Test void actualTicketPointNeedsNoSeparateRatioConfirmationAndCanDeriveMissingIncludedPrice(){
        for(var kind:List.of("rebate-fob","rebate-cfr-cif")){
            var r=ready();r.remove(List.of("taxIncludedPriceCny","fobInvoiceRatioConfirmed","fobInvoiceConfirmedFingerprint"));
            var p=product(r,kind);FobPricing.applyPurchase(p,r);
            assertFalse(p.at("/invoicePricing/verificationRequired").asBoolean());
            assertEquals(0,FobPricing.purchaseCost(p,BigDecimal.valueOf(45)).compareTo(BigDecimal.valueOf(54)));
        }
    }
    @Test void rejectsAuditedBadRatiosAndConflictingTaxPoints(){
        for(var amount:List.of("0","0.3","49","101")){
            var r=ready();r.put("taxIncludedPriceCny",new BigDecimal(amount));confirmed(r);
            assertThrows(RuntimeException.class,()->FobPricing.applyPurchase(product(r,"rebate-fob"),r),amount);
        }
        var r=ready().put("taxPoint",new BigDecimal("0.13"));confirmed(r);
        assertThrows(RuntimeException.class,()->FobPricing.applyPurchase(product(r,"rebate-fob"),r));
    }
    @Test void usesActualPointRatherThanReverseDerivingFromRoundedIncludedPrice(){
        var r=ready().put("purchasePriceCny",new BigDecimal("13.1")).put("taxIncludedPriceCny",new BigDecimal("14.15")).put("taxPoint",new BigDecimal("0.08")).put("tier2PriceCny",12);
        var p=product(r,"rebate-fob");FobPricing.applyPurchase(p,r);
        assertEquals(0,FobPricing.purchaseCost(p,BigDecimal.valueOf(12)).compareTo(new BigDecimal("12.96")));
        assertNotEquals(0,FobPricing.purchaseCost(p,BigDecimal.valueOf(12)).compareTo(BigDecimal.valueOf(12).multiply(new BigDecimal("14.15")).divide(new BigDecimal("13.1"),java.math.MathContext.DECIMAL128)));
    }
    @Test void includedTiersAreUsedDirectlyAndContradictoryAnchorsAreBlocked(){
        var r=ready().put("purchasePriceBasis","tax_included").put("purchasePriceCny",60).put("tier2PriceCny",54);confirmed(r);
        var p=product(r,"rebate-fob");FobPricing.applyPurchase(p,r);
        assertEquals(0,FobPricing.purchaseCost(p,BigDecimal.valueOf(54)).compareTo(BigDecimal.valueOf(54)));
        r.put("taxIncludedPriceCny",61);confirmed(r);
        assertThrows(RuntimeException.class,()->FobPricing.applyPurchase(product(r,"rebate-fob"),r));
    }
    @Test void priceChangesInvalidateConfirmationWhileMetadataEditsRetainIt(){
        var previous=ready();var changed=previous.deepCopy().put("tier2PriceCny",44);
        FobInvoicePolicy.maintainConfirmation(changed,previous,changed);assertFalse(FobInvoicePolicy.confirmed(changed));
        var metadata=previous.deepCopy().put("category","new");FobInvoicePolicy.maintainConfirmation(metadata,previous,metadata);assertTrue(FobInvoicePolicy.confirmed(metadata));
        changed.put("fobInvoiceRatioConfirmed",true);changed.remove("fobInvoiceConfirmedFingerprint");FobInvoicePolicy.maintainConfirmation(changed,previous,changed);assertTrue(FobInvoicePolicy.confirmed(changed));
    }
    @Test void oldConfirmationFlagsCannotDismissMissingActualTicketData(){
        var r=ready();r.remove("taxPoint");r.put("fobInvoiceRatioConfirmed",true).put("fobInvoiceConfirmedFingerprint","forged");
        var p=product(r,"rebate-fob");FobPricing.applyPurchase(p,r);
        assertTrue(p.at("/invoicePricing/verificationRequired").asBoolean());
        assertEquals(0.1,p.at("/invoicePricing/effectiveTaxPoint").asDouble());
    }
    @Test void rejectsZeroBaseOrMalformedTierOrdering(){
        for(var field:List.of("purchasePriceCny","tier2PriceCny")){
            var r=ready().put(field,0);confirmed(r);assertThrows(RuntimeException.class,()->FobPricing.applyPurchase(product(r,"rebate-fob"),r));
        }
        var r=ready().put("tier2MinQty",1);confirmed(r);assertThrows(RuntimeException.class,()->FobPricing.applyPurchase(product(r,"rebate-fob"),r));
        var missing=ready();missing.remove("tier2PriceCny");assertThrows(RuntimeException.class,()->product(missing,"rebate-fob"));
    }
    @Test void employeePayloadsDoNotContainEitherCoefficientIncludingHistory(){
        var r=ready();var p=product(r,"rebate-fob");FobPricing.applyPurchase(p,r);
        p.set("pricing",FobPricing.pricing(p,new BigDecimal("7.2"),policy()));FobCoefficientVisibility.redact(p);
        assertFalse(p.toString().contains("fobCoefficient"));assertFalse(p.toString().contains("fobInvoiceRatio\""));
        assertFalse(p.at("/invoicePricing/verificationRequired").asBoolean());
    }
}
