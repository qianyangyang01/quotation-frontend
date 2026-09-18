package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;

/** Price snapshots live in the existing record JSON. System and initial-sheet snapshots are immutable. */
final class CustomerQuotePrices {
    private CustomerQuotePrices() {}
    static Map<String, JsonNode> options(ObjectNode record) {
        var result = new LinkedHashMap<String, JsonNode>();
        var rows = record.has("quoteOptions") ? record.path("quoteOptions") : record.path("specifiedQuotes");
        int index = 0;
        for (var option : rows) {
            var id = option.path("id").asText("");
            if (id.isBlank()) id = "legacy-" + record.path("id").asText() + "-" + index;
            if (result.put(id, option) != null) throw AppException.unprocessable("报价渠道标识重复");
            index++;
        }
        if (result.isEmpty() && !record.has("quoteOptions")) {
            var option = JsonNodeFactory.instance.objectNode();
            option.putNull("quote1Usd"); option.putNull("quote2Usd"); option.putNull("quote3Usd");
            option.putNull("quoteCustomUsd");
            result.put("legacy-" + record.path("id").asText() + "-primary", option);
        }
        return result;
    }
    static ObjectNode validate(ObjectNode record, JsonNode value) {
        if (!value.isObject() || !value.path("quantities").isArray() || !value.path("rows").isArray()) throw AppException.unprocessable("客户报价格式错误");
        var quantities = value.path("quantities");
        if (quantities.isEmpty() || quantities.size() > 10) throw AppException.unprocessable("客户报价数量列须为1–10列");
        var seen = new HashSet<Long>();
        for (var quantity : quantities) {
            if (!quantity.isIntegralNumber() || quantity.asLong() < 0 || quantity.asLong() > 9007199254740991L ||
                (quantity.asLong() == 0 && record.path("customQuoteQuantity").asLong(0) > 0) || !seen.add(quantity.asLong()))
                throw AppException.unprocessable("客户报价数量须为不重复的正整数");
        }
        var options = options(record); var ids = new HashSet<String>();
        var clean = JsonNodeFactory.instance.objectNode(); clean.set("quantities", quantities.deepCopy()); var cleanRows = clean.putArray("rows");
        for (var row : value.path("rows")) {
            var id = row.path("optionId").asText("");
            if (!options.containsKey(id) || !ids.add(id) || !row.path("prices").isArray() || row.path("prices").size() != quantities.size())
                throw AppException.unprocessable("客户报价渠道或价格列不匹配");
            var prices = cleanRows.addObject().put("optionId", id).putArray("prices");
            for (var price : row.path("prices")) {
                if (price.isNull()) { prices.addNull(); continue; }
                if (!price.isNumber()) throw AppException.unprocessable("客户价格须为美元金额或空价格");
                var amount = new BigDecimal(price.asText());
                if (amount.signum() < 0 || amount.compareTo(new BigDecimal("999999999.99")) > 0 || amount.stripTrailingZeros().scale() > 2)
                    throw AppException.unprocessable("客户价格须为非负金额，最多两位小数");
                // Match the JSON mapper's numeric node type after a database round trip.
                // DecimalNode would compare unequal to its DoubleNode deep copy and trigger
                // a second dirty flush after the response version had already been captured.
                prices.add(amount.doubleValue());
            }
        }
        if (!ids.equals(options.keySet())) throw AppException.unprocessable("客户报价必须包含原报价的全部渠道");
        return clean;
    }
    static JsonNode originalPrice(ObjectNode record, JsonNode option, long quantity) {
        var field = quantity == 1 ? "quote1Usd" : quantity == 2 ? "quote2Usd" : quantity == 3 ? "quote3Usd" :
            quantity == record.path("customQuoteQuantity").asLong(0) ? "quoteCustomUsd" : null;
        return field == null ? null : option.hasNonNull(field) ? option.path(field) : NullNode.instance;
    }
    static void initialize(ObjectNode payload) {
        // Older clients remain valid. No artificial backfill of edits that were never saved.
        if (!payload.has("customerQuote")) { payload.remove("systemQuantityQuotes"); payload.remove("sheetQuote"); return; }
        var customer = validate(payload, payload.path("customerQuote"));
        var system = payload.has("systemQuantityQuotes") ? validate(payload, payload.path("systemQuantityQuotes")) : customer.deepCopy();
        if (!system.path("quantities").equals(customer.path("quantities"))) throw AppException.unprocessable("系统与客户报价数量不一致");
        var opts = options(payload);
        for (var row : system.path("rows")) {
            var prices = (ArrayNode) row.path("prices");
            for (int i=0;i<prices.size();i++) {
                var original = originalPrice(payload, opts.get(row.path("optionId").asText()), system.path("quantities").get(i).asLong());
                if (original != null) prices.set(i, original.deepCopy());
                else if (!payload.has("systemQuantityQuotes")) prices.set(i, NullNode.instance);
                else if (!prices.get(i).isNull() && new BigDecimal(prices.get(i).asText()).remainder(new BigDecimal("0.05")).signum()!=0)
                    throw AppException.unprocessable("新增数量系统报价须按0.05取整");
            }
        }
        payload.set("systemQuantityQuotes",system); payload.set("sheetQuote",customer.deepCopy()); payload.set("customerQuote",customer);
    }
    static void preparePatch(ObjectNode current, ObjectNode patch) {
        for (var field : List.of("systemQuantityQuotes","sheetQuote","quoteOptions","systemQuoteUsd","systemQuoteCny","weightSnapshot"))
            if (patch.has(field)) throw AppException.unprocessable("系统报价及首次报价单价格不可覆盖");
        if (patch.has("customerQuote")) patch.set("customerQuote", validate(current, patch.path("customerQuote")));
    }
}
