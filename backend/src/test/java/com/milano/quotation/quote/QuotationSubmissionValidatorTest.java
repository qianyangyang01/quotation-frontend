package com.milano.quotation.quote;

import com.milano.quotation.common.FieldValidationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.junit.jupiter.api.Assertions.*;

class QuotationSubmissionValidatorTest {
    private final QuotationSubmissionValidator validator = new QuotationSubmissionValidator();

    @Test void acceptsCompleteQuotationConditions() {
        assertDoesNotThrow(() -> validator.validate(valid()));
    }

    @Test void returnsEveryMissingRequiredField() {
        var error = assertThrows(FieldValidationException.class, () -> validator.validate(JsonNodeFactory.instance.objectNode()));
        assertEquals(9, error.fieldErrors().size());
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("customerName")));
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("monthlySalesEstimate")));
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("quoteOptions")));
    }

    @Test void rejectsInvalidEnumsAndWhitespaceCustomer() {
        var input = valid().put("customerName", "   ").put("quoteMode", "invalid").put("taxCustomerType", "C");
        var error = assertThrows(FieldValidationException.class, () -> validator.validate(input));
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("customerName")));
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("quoteMode")));
        assertTrue(error.fieldErrors().stream().anyMatch(item -> item.field().equals("taxCustomerType")));
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
                .put("taxCustomerType", "A").put("monthlySalesEstimate", "10");
        body.putArray("quoteOptions").addObject().put("country", "美国").put("channel", "渠道A");
        return body;
    }
}
