package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FobSpecialInvoiceTest {
    ObjectNode raw() { return FobQuotation.object().put("dataSource","standard").put("catalogState","ready").put("skuOrigin","manual")
        .put("minOrderQty",1).put("purchasePriceCny",100).put("tier2MinQty",100).put("tier2PriceCny",90).put("freeShipping","是")
        .put("taxIncludedPriceCny",103).put("taxPoint",new BigDecimal(".03")).put("invoiceType","普票")
        .put("specialTaxIncludedPriceCny",113).put("specialTaxPoint",new BigDecimal(".13")).put("specialInvoiceType","专票13"); }
    ObjectNode product(ObjectNode raw,String kind) { var p=FobQuotation.object().put("classification",kind);p.set("parsed",FobQuotation.standard(raw,true));FobPricing.applyPurchase(p,raw);return p; }
    void cost(ObjectNode p,String source,String expected) { assertEquals(0,new BigDecimal(expected).compareTo(FobPricing.purchaseCost(p,new BigDecimal(source)))); }
    @Test void bothRebateClassesUseSpecialGroupWhileOrdinaryUsesOriginalIncludedPrice() {
        var raw=raw();var snapshot=raw.deepCopy();
        for(var kind:List.of("rebate-fob","rebate-cfr-cif")) {
            var p=product(raw,kind);cost(p,"100","113");cost(p,"90","101.7");
            assertEquals("专票13",p.path("invoiceType").asText());
            assertEquals("special-invoice-columns",p.at("/invoicePricing/invoiceFieldSource").asText());
        }
        var ordinary=product(raw,"ordinary-fob");cost(ordinary,"100","103");cost(ordinary,"90","92.7");
        assertEquals("普票",ordinary.path("invoiceType").asText());assertEquals(snapshot,raw);
        raw.put("specialTaxIncludedPriceCny",999).put("specialInvoiceType","无票");
        cost(product(raw,"ordinary-fob"),"100","103");
    }
    @Test void blanksFallBackAsAGroupButPartialFieldsNeverBorrowOriginalTicketData() {
        var raw=raw().putNull("specialTaxIncludedPriceCny").putNull("specialTaxPoint").put("specialInvoiceType","")
            .put("invoiceType","专票3");
        var fallback=product(raw,"rebate-fob");cost(fallback,"100","103");
        assertEquals("original-invoice-columns",fallback.at("/invoicePricing/invoiceFieldSource").asText());
        raw.put("specialInvoiceType","专票13");
        var partial=product(raw,"rebate-fob");cost(partial,"100","110");
        assertTrue(partial.at("/invoicePricing/verificationRequired").asBoolean());
        assertEquals("专票点",partial.at("/invoicePricing/missingFields/0").asText());
        raw.put("specialTaxPoint",0).put("specialTaxIncludedPriceCny",100);
        cost(product(raw,"rebate-fob"),"90","90");
    }
    @Test void checksIncludedPriceAgainstFirstTierOnlyAndNeverAddsTaxTwice() {
        var badSpecial=raw().put("specialTaxIncludedPriceCny",101.7); assertThrows(RuntimeException.class,()->product(badSpecial,"rebate-fob"));
        var raw=raw();
        raw=raw().put("taxIncludedPriceCny",92.7);var bad=raw;
        assertThrows(RuntimeException.class,()->product(bad,"ordinary-fob"));
        raw=raw().put("purchasePriceBasis","tax_included").put("purchasePriceCny",113).put("tier2PriceCny",101.7);
        cost(product(raw,"rebate-fob"),"101.7","101.7");
        raw.put("taxIncludedPriceCny",113);cost(product(raw,"ordinary-fob"),"101.7","101.7");
    }
}
