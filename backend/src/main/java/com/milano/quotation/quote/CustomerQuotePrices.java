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
        if (value.has("hiddenOptionIds")) {
            var hidden = value.path("hiddenOptionIds");
            if (!hidden.isArray()) throw AppException.unprocessable("隐藏报价行格式错误");
            var hiddenIds = new HashSet<String>();
            var savedHidden = clean.putArray("hiddenOptionIds");
            for (var id : hidden) {
                if (!id.isTextual() || !options.containsKey(id.asText()) || !hiddenIds.add(id.asText()))
                    throw AppException.unprocessable("隐藏报价行须对应不重复的原报价渠道");
                savedHidden.add(id.asText());
            }
        }
        if (value.has("contact")) {
            var contact = value.path("contact");
            if (!contact.isObject()) throw AppException.unprocessable("报价单署名及联系方式格式错误");
            var savedContact = clean.putObject("contact");
            for (var field : List.of("agent", "whatsapp")) {
                var text = contact.path(field);
                if (!text.isTextual() || text.asText().length() > 40)
                    throw AppException.unprocessable("报价单署名及联系方式须为不超过40字的文本");
                savedContact.put(field, text.asText());
            }
        }
        if (value.has("sizeRules")) {
            var rules = value.path("sizeRules");
            if (!rules.isNull() && (!rules.isTextual() || rules.asText().length() > 2000))
                throw AppException.unprocessable("尺码规则须为不超过2000字的文本");
            clean.set("sizeRules", rules.deepCopy());
        }
        if (value.has("sizeRulesEnabled")) {
            if (!value.path("sizeRulesEnabled").isBoolean()) throw AppException.unprocessable("尺码规则开关格式错误");
            clean.put("sizeRulesEnabled", value.path("sizeRulesEnabled").asBoolean());
        }
        if (value.has("photos")) {
            var photos = value.path("photos");
            if (!photos.isArray() || photos.size()>6) throw AppException.unprocessable("报价单图片最多6张");
            var savedPhotos = clean.putArray("photos");
            for (var photo : photos) {
                if (!photo.path("assetId").isTextual() || !photo.path("assetId").asText().matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") ||
                    !photo.path("name").isTextual() || photo.path("name").asText().length()>255) throw AppException.unprocessable("报价单图片须为已上传的图片引用");
                savedPhotos.addObject().put("assetId",photo.path("assetId").asText()).put("name",photo.path("name").asText());
            }
        }
        if (value.has("showPhotos")) {
            if (!value.path("showPhotos").isBoolean()) throw AppException.unprocessable("图片显示开关格式错误");
            clean.put("showPhotos",value.path("showPhotos").asBoolean());
        }
        return clean;
    }
    private static void rowOrder(ObjectNode record, ObjectNode customer, JsonNode value, boolean retainExisting) {
        if (value.isMissingNode()) return;
        if (!value.isArray()) throw AppException.unprocessable("报价行顺序格式错误");
        var allowed = new HashSet<String>();
        options(record).keySet().forEach(id -> allowed.add("option:" + id));
        customer.path("averagePlans").forEach(plan -> allowed.add("average:" + plan.path("id").asText()));
        var seen = new HashSet<String>();
        var saved = customer.putArray("rowOrder");
        for (var id : value) {
            // Older clients may delete a plan without sending the new presentation field.
            if (retainExisting && id.isTextual() && !allowed.contains(id.asText())) continue;
            if (!id.isTextual() || !allowed.contains(id.asText()) || !seen.add(id.asText()))
                throw AppException.unprocessable("报价行顺序须对应不重复的原报价渠道或综合方案");
            saved.add(id.asText());
        }
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
        system.remove("sizeRules");
        system.remove("sizeRulesEnabled");
        system.remove("contact");
        system.remove("hiddenOptionIds");
        system.remove("photos");
        system.remove("showPhotos");
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
        payload.set("systemQuantityQuotes",system);
        if (payload.path("customerQuote").has("averagePlans")) customer.set("averagePlans", AverageQuotePlans.validate(payload, customer, payload.path("customerQuote").path("averagePlans")));
        rowOrder(payload, customer, payload.path("customerQuote").path("rowOrder"), false);
        payload.set("sheetQuote",customer.deepCopy()); payload.set("customerQuote",customer);
    }
    static void preparePatch(ObjectNode current, ObjectNode patch) {
        for (var field : List.of("systemQuantityQuotes","sheetQuote","quoteOptions","systemQuoteUsd","systemQuoteCny","weightSnapshot"))
            if (patch.has(field)) throw AppException.unprocessable("系统报价及首次报价单价格不可覆盖");
        if (patch.has("customerQuote")) {
            var customer = validate(current, patch.path("customerQuote"));
            for (var field : List.of("sizeRules", "sizeRulesEnabled", "photos", "showPhotos")) {
                if (!customer.has(field)) {
                    var previous = current.path("customerQuote").path(field);
                    if (previous.isMissingNode()) previous = current.path("sheetQuote").path(field);
                    if (!previous.isMissingNode()) customer.set(field, previous.deepCopy());
                }
            }
            // A price-only update from an older client must retain the saved header.
            if (!customer.has("contact")) {
                var previous = current.path("customerQuote").path("contact");
                if (previous.isMissingNode()) previous = current.path("sheetQuote").path("contact");
                if (previous.isObject()) customer.set("contact", previous.deepCopy());
            }
            // Older price-only clients must not silently restore hidden routes.
            if (!customer.has("hiddenOptionIds")) {
                var previous = current.path("customerQuote").path("hiddenOptionIds");
                if (previous.isMissingNode()) previous = current.path("sheetQuote").path("hiddenOptionIds");
                if (previous.isArray()) customer.set("hiddenOptionIds", previous.deepCopy());
            }
            var plans = patch.path("customerQuote").path("averagePlans");
            if (plans.isMissingNode()) {
                plans = current.path("customerQuote").path("averagePlans");
                if (plans.isMissingNode()) plans = current.path("sheetQuote").path("averagePlans");
            }
            if (!plans.isMissingNode()) customer.set("averagePlans", AverageQuotePlans.validate(current, customer, plans));
            var order = patch.path("customerQuote").path("rowOrder");
            var retainOrder = order.isMissingNode();
            if (retainOrder) {
                order = current.path("customerQuote").path("rowOrder");
                if (order.isMissingNode()) order = current.path("sheetQuote").path("rowOrder");
            }
            rowOrder(current, customer, order, retainOrder);
            patch.set("customerQuote", customer);
        }
    }
}
