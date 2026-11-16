package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.*;
import com.milano.quotation.common.AppException;
import com.milano.quotation.logistics.LogisticsQuotationGuard;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BatchShippingQuotationTest {
    static ObjectNode input(int count) {
        var root = BatchSingleQuotationTest.input(count);
        for (int i = 0; i < count; i++) {
            var item = (ObjectNode) root.path("items").get(i);
            item.put("entryId", Integer.toString(i + 1));
            var in = (ObjectNode) item.path("input");
            in.put("quoteMode", "shipping-only").put("primarySku", "").put("shippingNote", "相同备注");
            in.putObject("manualPricing").put("costCny", 0).put("weightGrams", 200);
            var label = (i + 1) + " · 200g · 相同备注";
            ((ObjectNode) item.at("/sheet/rows/0")).put("sku", label);
            ((ObjectNode) root.at("/sheet/rows/" + i)).put("sku", label);
        }
        return root;
    }
    @Test void equalWeightsBecomeTwentyIndependentInputsAndNeverMutateOriginal() {
        var request = input(20); var before = request.deepCopy();
        var service = BatchSingleQuotationTest.service();
        var body = JsonNodeFactory.instance.objectNode().put("kind", "shipping-only"); body.set("document", request);
        var result = new BatchQuotationSubmission(service, mock(FobQuotation.class)).prepare(body);
        assertEquals(20, result.size()); assertEquals(before, request);
        assertTrue(result.stream().allMatch(row -> row.path("primarySku").asText().isEmpty()));
        assertTrue(result.stream().allMatch(row -> row.at("/manualPricing/weightGrams").asInt() == 200));
        assertThrows(AppException.class, () -> service.prepare(request));
    }
    @Test void actualBrowserCapturePassesRealSubmissionValidationWithNewQuantityAndAverages() throws Exception {
        try (var stream = getClass().getResourceAsStream("/quotes/batch-shipping-browser.json")) {
            var document = new tools.jackson.databind.json.JsonMapper().readTree(stream);
            var service = new BatchSingleQuotation(new QuotationSubmissionValidator(), mock(QuotationReadinessService.class), mock(LogisticsQuotationGuard.class));
            var result = service.prepareShipping(document);
            assertEquals(20, result.path("items").size());
            for (var item : result.path("items")) {
                assertEquals(4, item.at("/input/customerQuote/quantities").size());
                assertEquals(1, item.at("/input/customerQuote/averagePlans").size());
                assertEquals("JerryFulfillment Batch Shipping Quote", item.at("/input/shippingBatchSheet/title").asText());
            }
        }
    }
    @Test void validatesEveryEntryBeforePersistenceAndPropagatesStaleSource() {
        var validator = mock(QuotationSubmissionValidator.class); var readiness = mock(QuotationReadinessService.class); var logistics = mock(LogisticsQuotationGuard.class);
        var service = new BatchSingleQuotation(validator, readiness, logistics);
        service.prepareShipping(input(20));
        verify(validator, times(20)).validate(any()); verify(validator, times(20)).validateQuotePricing(any());
        verify(readiness, times(20)).assertCanCreate(any()); verify(logistics, times(20)).validate(any());
        doThrow(AppException.conflict("价格版本已变化")).when(logistics).validate(any());
        assertThrows(AppException.class, () -> service.prepareShipping(input(2)));
    }
    @Test void rejectsMixedModesDuplicateIdentityBadWeightsAndTamperedPrice() {
        var service = BatchSingleQuotationTest.service();
        var duplicate = input(2); ((ObjectNode) duplicate.at("/items/1")).put("entryId", "1");
        assertThrows(AppException.class, () -> service.prepareShipping(duplicate));
        for (var mode : java.util.List.of("single", "freight-trial", "bundle", "fob")) {
            var mixed = input(2); ((ObjectNode) mixed.at("/items/1/input")).put("quoteMode", mode);
            assertThrows(AppException.class, () -> service.prepareShipping(mixed));
        }
        for (double weight : new double[]{0, -1, .0001, 1000001}) {
            var invalid = input(2); ((ObjectNode) invalid.at("/items/1/input/manualPricing")).put("weightGrams", weight);
            assertThrows(AppException.class, () -> service.prepareShipping(invalid));
        }
        var price = input(2); ((ArrayNode) price.at("/sheet/rows/1/prices")).set(0, IntNode.valueOf(1));
        assertThrows(AppException.class, () -> service.prepareShipping(price));
        var cost = input(2); ((ObjectNode) cost.at("/items/1/input/manualPricing")).put("costCny", 1);
        assertThrows(AppException.class, () -> service.prepareShipping(cost));
    }
}
