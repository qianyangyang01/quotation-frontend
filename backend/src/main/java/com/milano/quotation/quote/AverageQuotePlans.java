package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import java.math.*;
import java.util.*;

/** Derived plans never replace real routes. Every source is checked against the immutable system snapshot. */
final class AverageQuotePlans {
    private AverageQuotePlans() {}
    static ArrayNode validate(ObjectNode record, JsonNode customer, JsonNode plans) {
        if (!plans.isArray() || plans.size() > 20) throw AppException.unprocessable("综合报价方案须为数组，最多20个");
        var clean = JsonNodeFactory.instance.arrayNode();
        var ids = new HashSet<String>();
        var options = CustomerQuotePrices.options(record);
        for (var plan : plans) {
            var id = text(plan, "id", 80);
            if (!id.matches("[A-Za-z0-9_-]+") || !ids.add(id)) throw AppException.unprocessable("综合方案标识无效或重复");
            var mode = text(plan, "mode", 10); var display = text(plan, "display", 10);
            if (!Set.of("equal", "weighted").contains(mode) || !Set.of("summary", "details").contains(display)) throw AppException.unprocessable("综合报价计算或展示方式无效");
            var quantities = plan.path("quantities");
            if (!quantities.isArray() || quantities.size() != customer.path("quantities").size()) throw AppException.unprocessable("综合报价数量列不匹配");
            var quantitySet = new HashSet<Long>();
            for (var q : quantities) {
                if (!q.isIntegralNumber() || q.asLong() <= 0 || !quantitySet.add(q.asLong())) throw AppException.unprocessable("综合报价数量须为不重复的正整数");
            }
            for (var q : customer.path("quantities")) if (!quantitySet.contains(q.asLong())) throw AppException.unprocessable("综合报价数量列不匹配");
            var members = plan.path("members");
            if (!members.isArray() || members.size() < 2 || members.size() > options.size()) throw AppException.unprocessable("综合报价至少选择两条渠道");
            var saved = clean.addObject().put("id", id).put("mode", mode).put("display", display);
            saved.put("provider", ascii(plan, "provider", false)); saved.put("shippingTime", ascii(plan, "shippingTime", true));
            saved.set("quantities", quantities.deepCopy());
            var savedMembers = saved.putArray("members");
            var memberIds = new HashSet<String>();
            String scope = null; BigDecimal weightTotal = BigDecimal.ZERO;
            var totals = new ArrayList<BigDecimal>(Collections.nCopies(quantities.size(), BigDecimal.ZERO));
            for (var member : members) {
                var optionId = text(member, "optionId", 1000); var option = options.get(optionId);
                if (option == null || !memberIds.add(optionId) || (option.has("available") && !option.path("available").asBoolean())) throw AppException.unprocessable("综合报价来源渠道不存在、重复或不可用");
                for (var hidden : customer.path("hiddenOptionIds")) if (hidden.asText().equals(optionId)) throw AppException.unprocessable("请先移除综合方案后再隐藏来源渠道");
                var nextScope = scope(option);
                if (scope != null && !scope.equals(nextScope)) throw AppException.unprocessable("综合报价须为相同国家和税费口径；澳大利亚1–4区可自由组合，其他区域须一致");
                scope = nextScope;
                var weight = amount(member.path("weight"), false);
                if (weight.signum() <= 0 || weight.compareTo(new BigDecimal("100")) > 0) throw AppException.unprocessable("渠道权重须大于0且不超过100");
                weightTotal = weightTotal.add(weight);
                var savedMember = savedMembers.addObject().put("optionId", optionId).put("weight", mode.equals("equal") ? 1d : weight.doubleValue());
                var sourcePrices = savedMember.putArray("sourcePrices");
                if (!member.path("sourcePrices").isArray() || member.path("sourcePrices").size() != quantities.size()) throw AppException.unprocessable("综合报价来源价格列不匹配");
                for (int i = 0; i < quantities.size(); i++) {
                    var value = systemPrice(record, optionId, option, quantities.get(i).asLong());
                    if (value == null || value.isNull()) throw AppException.unprocessable("综合报价来源缺少系统价格，不能按0计算");
                    var price = amount(value, false);
                    if (price.compareTo(amount(member.path("sourcePrices").get(i), false)) != 0) throw AppException.unprocessable("综合报价来源价格已变化，请重新生成");
                    sourcePrices.add(price.doubleValue());
                    totals.set(i, totals.get(i).add(price.multiply(mode.equals("equal") ? BigDecimal.ONE : weight)));
                }
            }
            if (mode.equals("weighted") && weightTotal.compareTo(new BigDecimal("100")) != 0) throw AppException.unprocessable("渠道权重合计必须为100%");
            if (!plan.path("prices").isArray() || plan.path("prices").size() != quantities.size() || !plan.path("systemPrices").isArray() || plan.path("systemPrices").size() != quantities.size()) throw AppException.unprocessable("综合报价金额列不匹配");
            var system = saved.putArray("systemPrices"); var prices = saved.putArray("prices");
            for (int i = 0; i < quantities.size(); i++) {
                var expected = totals.get(i).divide(mode.equals("equal") ? BigDecimal.valueOf(members.size()) : new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                if (expected.compareTo(amount(plan.path("systemPrices").get(i), false)) != 0) throw AppException.unprocessable("综合报价计算结果不一致，请重新生成");
                system.add(expected.doubleValue());
                var price = amount(plan.path("prices").get(i), true);
                if (price == null) prices.addNull(); else prices.add(price.doubleValue());
            }
        }
        return clean;
    }
    static JsonNode systemPrice(ObjectNode record, String id, JsonNode option, long quantity) {
        var snapshot = record.path("systemQuantityQuotes");
        for (int i = 0; i < snapshot.path("quantities").size(); i++) if (snapshot.path("quantities").get(i).asLong() == quantity)
            for (var row : snapshot.path("rows")) if (row.path("optionId").asText().equals(id)) return row.path("prices").path(i);
        return CustomerQuotePrices.originalPrice(record, option, quantity);
    }
    static String scope(JsonNode option) {
        var key = JsonNodeFactory.instance.arrayNode();
        var country = option.hasNonNull("countryCode") && !option.path("countryCode").asText().isBlank()
            ? option.path("countryCode").asText() : option.path("country").asText();
        boolean australia = country.equalsIgnoreCase("AU") || country.equals("澳大利亚") || country.equalsIgnoreCase("Australia");
        key.add(australia ? "AU" : country);
        var region = option.path("quoteRegion").asText("");
        var zone = region.replaceAll("[（）()\\s]", "").replaceFirst("^澳大利亚", "")
            .replace("一区", "1区").replace("二区", "2区").replace("三区", "3区").replace("四区", "4区");
        key.add(australia && zone.matches("[1-4]区") ? "@AU:zones-1-4" : region);
        for (var field : List.of("taxFeeMode", "taxIncluded", "taxConfigured", "taxRatePercent")) key.add(option.hasNonNull(field) ? option.path(field) : NullNode.instance);
        return key.toString();
    }
    static String text(JsonNode node, String field, int max) {
        var value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) throw AppException.unprocessable("综合报价字段无效：" + field);
        return value.asText();
    }
    static String ascii(JsonNode node, String field, boolean blank) {
        var value = node.path(field);
        if (!value.isTextual() || value.asText().length() > 80 || (!blank && value.asText().isBlank()) || !value.asText().matches("[ -~]*")) throw AppException.unprocessable("方案名称和时效须为80字以内英文文本");
        return value.asText();
    }
    static BigDecimal amount(JsonNode value, boolean nullable) {
        if (value != null && value.isNull() && nullable) return null;
        if (value == null || !value.isNumber()) throw AppException.unprocessable("综合报价金额或权重格式错误");
        var amount = new BigDecimal(value.asText());
        if (amount.signum() < 0 || amount.compareTo(new BigDecimal("999999999.99")) > 0 || amount.stripTrailingZeros().scale() > 2) throw AppException.unprocessable("金额或权重须为非负数，最多两位小数");
        return amount;
    }
}
