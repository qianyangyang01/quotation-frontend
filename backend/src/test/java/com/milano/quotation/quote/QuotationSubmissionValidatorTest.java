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
        input.putArray("bundleItems").addObject().put("sku", "SKU-1").put("quantityPerSet", 2);
        input.withArray("bundleItems").addObject().put("sku", "SKU-2").put("quantityPerSet", 1);
        assertDoesNotThrow(() -> validator.validate(input));
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
