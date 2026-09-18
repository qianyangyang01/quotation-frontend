package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.Set;

final class QuotationFinanceReview {
    static final Set<String> FIELDS = Set.of("financeReviewStatus", "financeReviewedAt", "financeReviewedBy", "financeReviewedAccount");
    static final Set<String> STATUSES = Set.of("pending", "approved", "rejected");
    private QuotationFinanceReview() {}
    static void initialize(ObjectNode payload) {
        FIELDS.forEach(payload::remove);
        payload.put("financeReviewStatus", "pending");
    }
    static void rejectDirectPatch(ObjectNode patch) {
        if (FIELDS.stream().anyMatch(patch::has)) throw AppException.unprocessable("财务审核请使用管理员审核操作");
    }
    static boolean pricesChanged(ObjectNode current, ObjectNode patch) {
        if (!patch.has("customerQuote")) return false;
        JsonNode before = current.hasNonNull("customerQuote") ? current.path("customerQuote") : current.path("sheetQuote");
        return !QuotationConfirmation.cells(before).equals(QuotationConfirmation.cells(patch.path("customerQuote")));
    }
}
