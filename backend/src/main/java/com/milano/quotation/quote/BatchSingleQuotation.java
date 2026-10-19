package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import com.milano.quotation.logistics.LogisticsQuotationGuard;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import java.util.*;

/** Separate document contract. Ordinary quotation modes never dispatch through this service. */
@Service
public class BatchSingleQuotation {
    private final QuotationSubmissionValidator validator;
    private final QuotationReadinessService readiness;
    private final LogisticsQuotationGuard logistics;
    public BatchSingleQuotation(QuotationSubmissionValidator validator, QuotationReadinessService readiness, LogisticsQuotationGuard logistics) {
        this.validator = validator; this.readiness = readiness; this.logistics = logistics;
    }
    public ObjectNode prepare(JsonNode request) {
        if (!request.isObject() || request.toString().length() > 4_000_000 || request.path("schemaVersion").asInt() != 1
            || !request.path("items").isArray() || request.path("items").isEmpty() || request.path("items").size() > 50)
            throw AppException.unprocessable("批量单品报价须包含1至50个商品，且不超过4MB");
        var out = JsonNodeFactory.instance.objectNode().put("schemaVersion", 1);
        var items = out.putArray("items"); var seen = new HashSet<String>();
        ObjectNode merged = null; ArrayNode mergedRows = null; JsonNode firstInput = null;
        int itemIndex = 0;
        for (var item : request.path("items")) {
            if (!(item.path("input") instanceof ObjectNode original) || !(item.path("sheet") instanceof ObjectNode sheetInput))
                throw AppException.unprocessable("批量商品与报价单格式错误");
            var input = original.deepCopy(); var sku = input.path("primarySku").asText().trim().toUpperCase(Locale.ROOT);
            if (!"single".equals(input.path("quoteMode").asText()) || !sku.matches("[A-Z0-9][A-Z0-9._/-]{0,95}") || !seen.add(sku)
                || input.has("fob") || input.has("batchSingle"))
                throw AppException.unprocessable("仅允许不重复的单品 SKU 报价");
            input.put("primarySku", sku);
            if (input.path("quoteOptions").size() > 100) throw AppException.unprocessable("每个 SKU 最多100条渠道");
            if (firstInput == null) firstInput = input;
            else for (var field : List.of("customerName", "customerGrade", "exchangeRate", "commissionThreshold"))
                if (!Objects.equals(firstInput.path(field), input.path(field))) throw AppException.unprocessable("各 SKU 的客户、等级、汇率及佣金条件必须一致");
            validator.validate(input);
            validator.validateQuotePricing(input);
            readiness.assertCanCreate(input);
            logistics.validate(input);
            CustomerQuotePrices.initialize(input);
            var sheet = validateSheet(input, sheetInput, sku);
            if (merged == null) { merged = sheet.deepCopy(); mergedRows = merged.putArray("rows"); }
            else if (!merged.path("quantityLabels").equals(sheet.path("quantityLabels"))) throw AppException.unprocessable("各 SKU 的数量列必须一致");
            for (var row : sheet.path("rows")) {
                var copy = (ObjectNode)row.deepCopy(); copy.put("key", itemIndex + ":" + row.path("key").asText());
                copy.put("number", mergedRows.size() + 1); mergedRows.add(copy);
            }
            if (mergedRows.size() > 500) throw AppException.unprocessable("合并报价最多500条渠道明细");
            // Never store caller-supplied ownership/lifecycle state as an ordinary quotation.
            input.remove(List.of("id", "no", "status", "revisions", "lifecycleState", "financeReviewStatus", "salespersonAccount", "salespersonName"));
            var savedItem = items.addObject(); savedItem.set("input", input); savedItem.set("sheet", sheet);
            itemIndex++;
        }
        // The shared editor permits moving rows across SKU groups. Accept only a permutation
        // of the independently validated item rows, never caller-supplied prices or extra rows.
        if (!(request.path("sheet") instanceof ObjectNode requestedSheet)) throw AppException.unprocessable("合并报价单格式错误");
        var expectedMeta = merged.deepCopy(); expectedMeta.remove("rows");
        var requestedMeta = requestedSheet.deepCopy(); requestedMeta.remove("rows");
        if (!expectedMeta.equals(requestedMeta) || !requestedSheet.path("rows").isArray() || requestedSheet.path("rows").size() != mergedRows.size())
            throw AppException.unprocessable("合并报价单与逐 SKU 快照不一致，请重新生成");
        var expectedRows = new HashMap<String, ObjectNode>();
        for (var row : mergedRows) expectedRows.put(row.path("key").asText(), (ObjectNode) row);
        var ordered = JsonNodeFactory.instance.arrayNode();
        for (var row : requestedSheet.path("rows")) {
            var expected = expectedRows.remove(row.path("key").asText());
            if (expected == null) throw AppException.unprocessable("合并报价单包含重复或未知渠道");
            expected.put("number", ordered.size() + 1);
            if (!expected.equals(row)) throw AppException.unprocessable("合并报价单与逐 SKU 快照不一致，请重新生成");
            ordered.add(expected);
        }
        merged.set("rows", ordered);
        out.put("customerName", firstInput.path("customerName").asText().trim()); out.set("sheet", merged);
        return out;
    }
    private static ObjectNode validateSheet(ObjectNode input, ObjectNode sheet, String sku) {
        var quantities = input.path("customerQuote").path("quantities");
        if (!sheet.path("rows").isArray() || sheet.path("rows").isEmpty() || sheet.path("issues").size() > 0
            || !sheet.path("quantityLabels").isArray() || sheet.path("quantityLabels").size() != quantities.size()
            || sheet.path("fobBatch").asBoolean() || sheet.path("showQuantityRange").asBoolean())
            throw AppException.unprocessable(sku + "：报价单尚未准备好");
        for (int i = 0; i < quantities.size(); i++) {
            long q = quantities.get(i).asLong();
            var unit = input.path("customerQuote").path("quantityUnits").path(Long.toString(q)).asText(q == 1 ? "pc" : "pcs");
            if (q <= 0 || !sheet.path("quantityLabels").get(i).asText().equals(q + " " + unit))
                throw AppException.unprocessable("报价单数量标题与价格数量不一致");
        }
        var options = new HashMap<String, JsonNode>(); var prices = new HashMap<String, JsonNode>();
        var hidden = new HashSet<String>(); input.path("customerQuote").path("hiddenOptionIds").forEach(id -> hidden.add(id.asText()));
        var averages = new HashMap<String, JsonNode>();
        for (var plan : input.path("customerQuote").path("averagePlans")) {
            averages.put("average:" + plan.path("id").asText(), plan);
            if ("summary".equals(plan.path("display").asText())) plan.path("members").forEach(member -> hidden.add(member.path("optionId").asText()));
        }
        for (var option : input.path("quoteOptions")) {
            if (!hidden.contains(option.path("id").asText())) {
                var key = option.path("quoteSheetKey").asText();
                if (key.isBlank() || options.put(key, option) != null) throw AppException.unprocessable("渠道报价单标识缺失或重复");
            }
        }
        input.path("customerQuote").path("rows").forEach(row -> prices.put(row.path("optionId").asText(), row.path("prices")));
        if (sheet.path("rows").size() != options.size() + averages.size()) throw AppException.unprocessable(sku + "：报价单渠道不完整");
        var seen = new HashSet<String>();
        for (var row : sheet.path("rows")) {
            var key = row.path("key").asText(); var option = options.get(key);
            var average = averages.get(key);
            if (average != null) {
                if (!seen.add(key) || !sku.equals(row.path("sku").asText()) || !average.path("id").equals(row.path("averageId"))
                    || !samePrices(row.path("prices"), average.path("prices"))) throw AppException.unprocessable(sku + "：综合报价与保存快照不一致");
                continue;
            }
            if (!row.isObject() || option == null || !seen.add(key) || !sku.equals(row.path("sku").asText())
                || !samePrices(row.path("prices"), prices.get(option.path("id").asText())))
                throw AppException.unprocessable(sku + "：报价单价格或 SKU 与保存快照不一致");
        }
        return sheet.deepCopy();
    }
    static boolean samePrices(JsonNode a, JsonNode b) {
        if (a == null || b == null || !a.isArray() || !b.isArray() || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            var x = a.get(i); var y = b.get(i);
            if (x.isNull() && y.isNull()) continue;
            if (!x.isNumber() || !y.isNumber() || x.decimalValue().compareTo(y.decimalValue()) != 0) return false;
        }
        return true;
    }
}
