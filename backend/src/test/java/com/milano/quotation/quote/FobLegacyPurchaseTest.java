package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FobLegacyPurchaseTest {
    static ObjectNode source() {
        return FobQuotation.object().put("sku", "TC2600082").put("dataSource", "legacy_2026")
            .put("catalogState", "ready").put("quoteReady", true).put("skuOrigin", "manual")
            .put("weightG", 65).put("minOrderQty", 1).put("sourceQuotedPriceCny", 4)
            .put("purchasePriceCny", new BigDecimal("4.04")).put("taxIncludedPriceCny", new BigDecimal("4.04"))
            .put("purchasePriceBasis", "tax_included").put("invoiceType", "普票1").put("taxPoint", new BigDecimal("0.01"))
            .put("freeShipping", "否").put("singleFreightCny", 2).put("freight100Cny", 21);
    }
    static ObjectNode product(ObjectNode raw, String kind) {
        var p = FobQuotation.object().put("classification", kind);
        p.set("parsed", FobQuotation.standard(raw, true));
        FobPricing.applyPurchase(p, raw);
        return p;
    }
    @Test void includedLegacyCostAndFreightAreUsedOnceWithoutChangingProcurement() {
        var raw = source(); var before = raw.deepCopy();
        var product = product(raw, "ordinary-fob");
        var config = FobQuotation.object().put("version", 0); config.set("value", FobPricing.defaults());
        var pricing = FobPricing.pricing(product, new BigDecimal("6.63"), config);
        assertEquals(4.04, pricing.at("/rows/0/taxIncludedCny").asDouble());
        assertEquals(.21, pricing.at("/rows/0/freightCny").asDouble());
        assertEquals(4.25, pricing.at("/rows/0/costCny").asDouble());
        assertEquals("0.77", pricing.at("/rows/0/declaredUsd").asText());
        assertEquals(before, raw);
        assertThrows(AppException.class, () -> FobQuotation.standard(raw));
    }
    @Test void eligibilityDoesNotBypassInvoiceValidationForAnyClass() {
        for (var kind : List.of("rebate-fob", "rebate-cfr-cif")) {
            assertThrows(AppException.class, () -> product(source(), kind));
            var raw = source().put("invoiceType", "专票1");
            var p = product(raw, kind);
            assertEquals(0, new BigDecimal("4.04").compareTo(FobPricing.purchaseCost(p, new BigDecimal("4.04"))));
        }
        var quoted = source().put("purchasePriceBasis", "quoted").put("purchasePriceCny", 4);
        assertEquals(4.04, product(quoted, "ordinary-fob").at("/invoicePricing/baseIncludedCny").asDouble());
        quoted.put("taxIncludedPriceCny", 5);
        assertThrows(AppException.class, () -> product(quoted, "ordinary-fob"));
    }
    @Test void incompleteDisabledTemplateAndUnsupportedSourcesRemainBlocked() {
        for (var state : List.of("pending_template", "disabled"))
            assertThrows(AppException.class, () -> product(source().put("catalogState", state), "ordinary-fob"));
        assertThrows(AppException.class, () -> product(source().put("skuOrigin", "system"), "ordinary-fob"));
        assertThrows(AppException.class, () -> product(source().put("dataSource", "unknown"), "ordinary-fob"));
        var incomplete = source().put("quoteReady", false); incomplete.putArray("quotationBlockingReasons").add("克重");
        assertTrue(assertThrows(AppException.class, () -> product(incomplete, "ordinary-fob")).getMessage().contains("克重"));
        var freight = source(); freight.remove("freight100Cny");
        assertTrue(assertThrows(AppException.class, () -> product(freight, "ordinary-fob")).getMessage().contains("100件总运费"));
        assertDoesNotThrow(() -> product(freight.put("freeShipping", "是"), "ordinary-fob"));
        var tier = source().put("tier2MinQty", 100);
        assertThrows(AppException.class, () -> product(tier, "ordinary-fob"));
    }
}
