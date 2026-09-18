package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;

final class QuotationConfirmation {
    private QuotationConfirmation() {}
    static void prepare(ObjectNode current, ObjectNode patch) {
        if (patch.has("quoteConfirmed") && (!patch.path("quoteConfirmed").isBoolean() || !patch.path("quoteConfirmed").asBoolean()))
            throw AppException.unprocessable("请使用确认报价操作");
        if (patch.has("quoteConfirmedAt") || patch.has("quoteConfirmedBy"))
            throw AppException.unprocessable("确认人和确认时间由系统记录");
        if (patch.has("quoteConfirmed") && patch.has("customerQuote"))
            throw AppException.unprocessable("请先保存客户报价，再确认报价");
        if (current.path("quoteConfirmed").asBoolean(false) && patch.has("customerQuote")) {
            var old = current.has("customerQuote") ? current.path("customerQuote") : current.path("sheetQuote");
            if (old.isMissingNode() || old.isNull()) {
                // Match the legacy UI's original 1 / 2 / 3 / custom columns by identity.
                var baseline = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                var quantities = new LinkedHashSet<Long>(List.of(1L,2L,3L,current.path("customQuoteQuantity").asLong(0)));
                var columns = baseline.putArray("quantities");quantities.forEach(columns::add);
                var rows = baseline.putArray("rows");
                for (var entry : CustomerQuotePrices.options(current).entrySet()) {
                    var prices = rows.addObject().put("optionId",entry.getKey()).putArray("prices");
                    for (var quantity : quantities) {
                        var original = CustomerQuotePrices.originalPrice(current,entry.getValue(),quantity);
                        prices.add(original == null ? tools.jackson.databind.node.NullNode.instance : original);
                    }
                }
                old = baseline;
            }
            if (!cells(old).equals(cells(patch.path("customerQuote")))) patch.put("quoteConfirmed", false);
        }
    }
    static Map<String, Map<Long, String>> cells(JsonNode snapshot) {
        var result = new TreeMap<String, Map<Long, String>>();
        for (var row : snapshot.path("rows")) {
            var prices = new TreeMap<Long, String>();
            for (int i = 0; i < snapshot.path("quantities").size(); i++) {
                var price = row.path("prices").path(i);
                prices.put(snapshot.path("quantities").get(i).asLong(), price.isNumber() ? new BigDecimal(price.asText()).stripTrailingZeros().toPlainString() : "blank");
            }
            result.put(row.path("optionId").asText(), prices);
        }
        return result;
    }
}
