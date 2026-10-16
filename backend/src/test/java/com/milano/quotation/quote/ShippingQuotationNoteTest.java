package com.milano.quotation.quote;

import com.milano.quotation.common.FieldValidationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShippingQuotationNoteTest {
    private final JsonMapper mapper = new JsonMapper();
    private ObjectNode shipping() { return new ManualQuotationTest().quote("shipping-only"); }

    @Test void normalizesOnlyThePersistedCopyAndNeverUsesTheNoteForSkuOrPricing() {
        var input = shipping().put("shippingNote", " \u3000TC2600009-1 客户自备 100%_/A\\B\u00a0 ")
            .put("productSummary", "客户端标题不能覆盖服务端快照");
        var before = input.deepCopy();
        new QuotationSubmissionValidator().validate(input);
        // Existing commission normalization is independent of the note.
        before.put("commissionThreshold", 1);
        assertEquals(before, input);
        var saved = input.deepCopy(); ShippingQuotationNote.snapshot(saved);
        assertEquals("TC2600009-1 客户自备 100%_/A\\B", saved.path("shippingNote").asText());
        assertEquals("仅代发货报价：TC2600009-1 客户自备 100%_/A\\B", saved.path("productSummary").asText());
        for (var field : List.of("primarySku", "manualPricing", "purchaseVersions", "financeVersions", "quoteOptions", "systemQuantityQuotes"))
            assertEquals(input.path(field), saved.path(field), field);
        assertEquals(before, input);
    }

    @Test void absentAndBlankNotesKeepLegacyTitleAndDoNotLeakIntoOtherModes() {
        for (var note : List.of("", " \t\r\n\u3000\u00a0 ")) {
            var input = shipping().put("shippingNote", note);
            ShippingQuotationNote.snapshot(input);
            assertFalse(input.has("shippingNote"));
            assertEquals("仅代发货报价", input.path("productSummary").asText());
        }
        var legacy = shipping(); ShippingQuotationNote.snapshot(legacy);
        assertEquals("仅代发货报价", legacy.path("productSummary").asText());
        for (var mode : List.of("single", "bundle", "freight-trial", "fob")) {
            var other = mapper.createObjectNode().put("quoteMode", mode).put("productSummary", "保留原标题");
            ShippingQuotationNote.snapshot(other);
            assertEquals("保留原标题", other.path("productSummary").asText());
            other.put("shippingNote", "SKU-1");
            assertThrows(FieldValidationException.class, () -> ShippingQuotationNote.validated(other), mode);
        }
    }

    @Test void validatesTypeAndUtf16LengthBeforeSavingOrDrafting() {
        for (var invalid : List.of("null", "1", "true", "{}", "[]", "[\"SKU\"]")) {
            var input = shipping(); input.set("shippingNote", mapper.readTree(invalid));
            assertThrows(FieldValidationException.class, () -> new QuotationSubmissionValidator().validate(input), invalid);
            assertThrows(FieldValidationException.class, () -> ShippingQuotationNote.normalizeDraft(input), invalid);
        }
        var exact = shipping().put("shippingNote", "  " + "中".repeat(200) + "  ");
        ShippingQuotationNote.snapshot(exact); assertEquals(200, exact.path("shippingNote").asText().length());
        var emoji = shipping().put("shippingNote", "😀".repeat(100));
        assertDoesNotThrow(() -> ShippingQuotationNote.snapshot(emoji));
        for (var note : List.of("中".repeat(201), "😀".repeat(100) + "x")) {
            var input = shipping().put("shippingNote", note);
            assertThrows(FieldValidationException.class, () -> ShippingQuotationNote.snapshot(input));
        }
    }
}
