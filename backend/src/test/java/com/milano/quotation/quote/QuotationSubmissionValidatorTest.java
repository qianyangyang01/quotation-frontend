package com.milano.quotation.quote;

import com.milano.quotation.common.FieldValidationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.*;

class QuotationSubmissionValidatorTest {
    @Test void validatesCommissionAndPreservesAdjustedSnapshots() {
        var old = valid(); validator.validate(old); assertEquals(1, old.path("commissionThreshold").asInt());
        var input = valid().put("commissionThreshold", .95).put("systemQuoteUsd", 6.35).put("systemQuoteCny", 42.55).put("exchangeRate", 6.7);
        validator.validate(input); validator.validateQuotePricing(input);
        assertEquals(6.35, input.path("systemQuoteUsd").asDouble());
        for (double bad : new double[]{0, -1, 1.01}) assertThrows(FieldValidationException.class, () -> validator.validate(valid().put("commissionThreshold", bad)));
        assertThrows(FieldValidationException.class, () -> validator.validate(valid().putNull("commissionThreshold")));
        assertThrows(FieldValidationException.class, () -> validator.validate(valid().put("commissionThreshold", "0.95")));
    }

    @Test void acceptsNewCustomerForSingleAndBundleAcrossMatrixModes() {
        for (var mode : new String[]{"common", "specified", "template"}) {
            var single = valid().put("customerGrade", "新客户").put("matrixMode", mode);
            assertDoesNotThrow(() -> validator.validate(single));
            var bundle = single.deepCopy().put("quoteMode", "bundle").put("primarySku", "SKU-1、SKU-2");
            addBundleItem(bundle, "SKU-1", 2, 0.2, 12, 1.5);
            addBundleItem(bundle, "SKU-2", 1, 0.35, 20, 0);
            assertDoesNotThrow(() -> validator.validate(bundle));
        }
        assertThrows(FieldValidationException.class, () -> validator.validate(valid().put("customerGrade", "NEW级客户")));
    }
    @Test void rejectsNegativeQuoteAmountsButAllowsMissingOptionalAmountsAndLosses() {
        var input = valid();
        ((tools.jackson.databind.node.ObjectNode) input.path("quoteOptions").get(0)).put("quoteCustomUsd", -10);
        assertThrows(FieldValidationException.class, () -> validator.validate(input));
        ((tools.jackson.databind.node.ObjectNode) input.path("quoteOptions").get(0)).putNull("quoteCustomUsd").put("profitCny", -1);
        assertDoesNotThrow(() -> validator.validate(input));
    }
    @Test void rejectsInvalidOutcomeAmountsQuantitiesAndStatus() {
        var patch = JsonNodeFactory.instance.objectNode().put("status", "won").put("actualQuoteCny", -999).put("dealQuantity", -2);
        assertThrows(FieldValidationException.class, () -> validator.validateUpdate(patch));
        patch.put("actualQuoteCny", 100).put("dealQuantity", 2);
        assertDoesNotThrow(() -> validator.validateUpdate(patch));
        patch.put("status", "fake"); assertThrows(FieldValidationException.class, () -> validator.validateUpdate(patch));
        patch.put("status", "won").putArray("dealLines").addObject().put("quantity", 1.5).put("unitPriceUsd", 10);
        assertThrows(FieldValidationException.class, () -> validator.validateUpdate(patch));
    }
    @Test void validatesFinalUsdAndConvertedCnyWithoutChangingHistoricalDeals() {
        var input = valid().put("systemQuoteUsd", 6.05).put("systemQuoteCny", 40.54).put("exchangeRate", 6.7);
        var option = (tools.jackson.databind.node.ObjectNode) input.path("quoteOptions").get(0);
        option.put("quote1Usd", 6.05).put("quoteCustomUsd", 6.10).put("quoteCny", 40.87);
        assertDoesNotThrow(() -> validator.validateQuotePricing(input));
        option.put("quote1Usd", 6.01);
        assertThrows(FieldValidationException.class, () -> validator.validateQuotePricing(input));
        option.put("quote1Usd", 6.05).put("quoteCny", 40.85);
        assertThrows(FieldValidationException.class, () -> validator.validateQuotePricing(input));
        assertDoesNotThrow(() -> validator.validateUpdate(JsonNodeFactory.instance.objectNode().put("actualQuoteUsd", 6.01)));
    }

    private final QuotationSubmissionValidator validator = new QuotationSubmissionValidator();

    @Test void acceptsCompleteQuotationConditions() {
        assertDoesNotThrow(() -> validator.validate(valid()));
    }

    @Test void returnsEveryMissingRequiredField() {
        var error = assertThrows(FieldValidationException.class, () -> validator.validate(JsonNodeFactory.instance.objectNode()));
        assertEquals(8, error.fieldErrors().size());
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("customerName")));
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("monthlySalesEstimate")));
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("quoteOptions")));
    }

    @Test void rejectsInvalidEnumsAndWhitespaceCustomer() {
        var input = valid().put("customerName", "   ").put("quoteMode", "invalid");
        var error = assertThrows(FieldValidationException.class, () -> validator.validate(input));
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("customerName")));
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("quoteMode")));
    }

    @Test void rejectsOverlongCustomerAndMissingChannelSelection() {
        var input = valid().put("customerName", "客".repeat(121));
        input.putArray("quoteOptions");
        var error = assertThrows(FieldValidationException.class, () -> validator.validate(input));
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("customerName")));
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("quoteOptions")));
    }

    @Test void acceptsBundleQuotationWithMultipleStructuredSkus() {
        var input = valid().put("quoteMode", "bundle").put("primarySku", "SKU-1、SKU-2");
        addBundleItem(input, "SKU-1", 2, 0.2, 12, 1.5);
        addBundleItem(input, "SKU-2", 1, 0.35, 20, 0);
        assertDoesNotThrow(() -> validator.validate(input));
    }

    @Test void rejectsIncompleteDuplicateAndMismatchedBundles() {
        var input = valid().put("quoteMode", "bundle").put("primarySku", "SKU-2、SKU-1");
        addBundleItem(input, "SKU-1", 0, 0, -1, -2);
        addBundleItem(input, "sku-1", 1, 0.2, 10, 0);
        var error = assertThrows(FieldValidationException.class, () -> validator.validate(input));
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("bundleItems")));
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("primarySku")));
    }

    @Test void rejectsBundleDetailsOnSingleQuotation() {
        var input = valid();
        addBundleItem(input, "SKU-1", 1, 0.2, 12, 0);
        var error = assertThrows(FieldValidationException.class, () -> validator.validate(input));
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("bundleItems")));
    }

    private void addBundleItem(tools.jackson.databind.node.ObjectNode input, String sku, int quantity, double weight, double price, double freight) {
        input.withArray("bundleItems").addObject().put("sku", sku).put("name", "商品" + sku)
                .put("quantityPerSet", quantity).put("effectiveWeightKg", weight)
                .put("purchaseUnitPriceCny", price).put("domesticFreightPerUnitCny", freight);
    }

    private tools.jackson.databind.node.ObjectNode valid() {
        var body = JsonNodeFactory.instance.objectNode();
        body.put("customerName", "客户A").put("quoteMode", "single").put("primarySku", "SKU-1")
                .put("productCategory", "服装").put("logisticsAttribute", "普货").put("customerGrade", "S级客户")
                .put("monthlySalesEstimate", "10");
        body.putArray("quoteOptions").addObject().put("country", "美国").put("channel", "渠道A");
        return body;
    }
}
