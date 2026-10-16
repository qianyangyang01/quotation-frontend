package com.milano.quotation.quote;

import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.FieldValidationException;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;

/** Shipping notes identify a manual quotation, never a purchase SKU or pricing input. */
final class ShippingQuotationNote {
    private ShippingQuotationNote() {}
    private static final int MAX_LENGTH = 200;

    static String validated(ObjectNode input) {
        var value = input.get("shippingNote");
        if (value == null) return "";
        if (!value.isTextual()) throw invalid("备注信息必须为文本");
        var note = value.asText().replaceAll("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$", "");
        if (note.length() > MAX_LENGTH) throw invalid("备注信息不能超过200字");
        if (!note.isEmpty() && !"shipping-only".equals(input.path("quoteMode").asText()))
            throw invalid("仅代发货报价才可填写备注信息");
        return note;
    }

    static void normalizeDraft(ObjectNode input) {
        var note = validated(input);
        if (note.isEmpty()) input.remove("shippingNote");
        else input.put("shippingNote", note);
    }

    /** Call on the persisted copy only, after idempotency lookup; old retries keep their snapshot. */
    static void snapshot(ObjectNode payload) {
        normalizeDraft(payload);
        if ("shipping-only".equals(payload.path("quoteMode").asText())) {
            var note = payload.path("shippingNote").asText("");
            payload.put("productSummary", "仅代发货报价" + (note.isEmpty() ? "" : "：" + note));
        }
    }

    private static FieldValidationException invalid(String message) {
        return new FieldValidationException(List.of(new ApiResponse.FieldError("shippingNote", message)));
    }
}
