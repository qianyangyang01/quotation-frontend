package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.Set;

final class QuotationFinanceReview {
    static final Set<String> FIELDS = QuotationReviewService.VIEW_FIELDS;
    static final Set<String> STATUSES = Set.of("pending", "approved", "rejected");
    private QuotationFinanceReview() {}
    static void initialize(ObjectNode payload) {
        FIELDS.forEach(payload::remove);
        payload.remove("_reviewVersion");
        payload.put("financeReviewStatus", "pending");
    }
    static void rejectDirectPatch(ObjectNode patch) {
        if (FIELDS.stream().anyMatch(patch::has) || patch.has("_reviewVersion")) throw AppException.unprocessable("财务审核请使用审核操作");
    }
    static boolean pricesChanged(ObjectNode current, ObjectNode patch) {
        if (patch.has("customerName") && !java.util.Objects.equals(current.get("customerName"),patch.get("customerName"))) return true;
        if (!patch.has("customerQuote")) return false;
        JsonNode before = current.hasNonNull("customerQuote") ? current.path("customerQuote") : current.path("sheetQuote");
        return !QuotationConfirmation.cells(before).equals(QuotationConfirmation.cells(patch.path("customerQuote")));
    }
}
