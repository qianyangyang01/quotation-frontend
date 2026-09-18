package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;

/** Validate the new snapshot without reinterpreting historical records that lack it. */
final class PackagingWeight {
    private PackagingWeight() {}
    static void draft(ObjectNode input) {
        if (!input.has("specialPackagingGrams")) return;
        special(input.path("specialPackagingGrams"));
    }
    private static BigDecimal number(JsonNode n) {
        if (!n.isNumber() || !Double.isFinite(n.asDouble())) throw AppException.unprocessable("包材重量必须为有效非负数");
        var value = new BigDecimal(n.asText());
        if (value.signum() < 0) throw AppException.unprocessable("包材重量不能为负数");
        return value;
    }
    private static BigDecimal special(JsonNode n) {
        var value = number(n);
        if (value.stripTrailingZeros().scale() > 0 || value.compareTo(new BigDecimal("100000")) > 0)
            throw AppException.unprocessable("特殊包装克重须为0至100000的整数");
        return value.divide(new BigDecimal("1000"));
    }
    private static BigDecimal quantity(JsonNode n) {
        var value = number(n);
        if (value.signum() == 0 || value.stripTrailingZeros().scale() > 0 || value.compareTo(new BigDecimal("9007199254740991")) > 0)
            throw AppException.unprocessable("包材快照数量须为正整数");
        return value;
    }
    private static void equal(JsonNode n, BigDecimal expected) {
        if (number(n).compareTo(expected) != 0) throw AppException.unprocessable("包材重量快照与计算依据不一致，请重新报价");
    }
    static void record(ObjectNode input) {
        if (!input.has("weightSnapshot")) return; // Old clients and records remain identifiable by the missing snapshot.
        var w = input.path("weightSnapshot");
        if (!w.isObject() || w.path("schemaVersion").asInt() != 1 || !"per-item-50g-1g-v1".equals(w.path("rule").asText()) || !"per-shipment".equals(w.path("specialPackagingScope").asText()))
            throw AppException.unprocessable("包材规则版本不合法，请刷新后重新报价");
        var extra = special(w.path("specialPackagingGrams"));
        if (!w.path("items").isArray() || w.path("items").isEmpty() || !w.path("quantities").isArray() || w.path("quantities").isEmpty())
            throw AppException.unprocessable("缺少包材重量依据");
        var base = BigDecimal.ZERO;
        var ordinary = BigDecimal.ZERO;
        for (var item : w.path("items")) {
            if (item.path("sku").asText("").isBlank()) throw AppException.unprocessable("包材快照缺少SKU");
            var unit = number(item.path("baseWeightKg"));
            var count = quantity(item.path("quantityPerSet"));
            var pack = unit.divide(new BigDecimal("0.05"), 0, RoundingMode.CEILING).multiply(new BigDecimal("0.001"));
            equal(item.path("standardPackagingWeightKg"), pack);
            base = base.add(unit.multiply(count)); ordinary = ordinary.add(pack.multiply(count));
        }
        var weights = new HashMap<BigDecimal, JsonNode>();
        for (var row : w.path("quantities")) {
            var q = quantity(row.path("quantity")).stripTrailingZeros();
            if (weights.put(q,row) != null) throw AppException.unprocessable("包材快照数量重复");
            equal(row.path("baseWeightKg"), base.multiply(q));
            equal(row.path("standardPackagingWeightKg"), ordinary.multiply(q));
            equal(row.path("specialPackagingWeightKg"), extra);
            equal(row.path("weightKg"), base.add(ordinary).multiply(q).add(extra));
        }
        for (var option : input.path("quoteOptions")) {
            var parcel = option.path("logisticsInput");
            var q = quantity(parcel.path("quantity")).stripTrailingZeros();
            var row = weights.get(q);
            if (row == null) throw AppException.unprocessable("物流重量缺少对应数量的包材快照");
            equal(parcel.path("baseWeightKg"), base.multiply(q));
            equal(parcel.path("standardPackagingWeightKg"), ordinary.multiply(q));
            equal(parcel.path("specialPackagingWeightKg"), extra);
            equal(parcel.path("packagingWeightKg"), ordinary.multiply(q).add(extra));
            equal(parcel.path("weightKg"), number(row.path("weightKg")));
            for (var sample : option.path("logisticsSamples")) {
                var sampleQ = quantity(sample.path("quantity")).stripTrailingZeros();
                equal(sample.path("input").path("weightKg"), base.add(ordinary).multiply(sampleQ).add(extra));
            }
        }
    }
}
