package com.milano.quotation.purchase;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Mirrors the catalog's display tiers; never calculates or persists quotation costs. */
final class PurchaseCatalogExportPrices {
    private PurchaseCatalogExportPrices() {}

    static List<String> tiers(JsonNode value, boolean fob) {
        var result = new ArrayList<String>();
        if (fob) {
            for (var tier : value.path("fobTiers")) {
                var min = number(tier.path("minQty"));
                var price = number(tier.path("unitPriceCny"));
                if (min == null || price == null) { result.add(""); continue; }
                result.add(range(min, number(tier.path("maxQty")), tier.path("unit").asText("件"))
                    + "；¥" + amount(price, false) + "/" + tier.path("unit").asText("件"));
            }
            return result;
        }
        boolean legacy = "legacy_2026".equals(value.path("dataSource").asText());
        var candidates = new ArrayList<Tier>();
        for (int i = 1; i <= 5; i++) {
            var min = number(value.path(i == 1 ? "minOrderQty" : "tier" + i + "MinQty"));
            var price = number(value.path(i == 1 ? "purchasePriceCny" : "tier" + i + "PriceCny"));
            if (i == 1 && legacy && min == null) min = BigDecimal.ONE;
            if (min != null && min.signum() > 0 && price != null && price.signum() >= 0)
                candidates.add(new Tier(min, price, i == 1));
        }
        candidates.sort(Comparator.comparing(Tier::min));
        for (int i = 0; i < candidates.size(); i++) {
            var tier = candidates.get(i);
            var min = floor(tier.min());
            var max = i + 1 < candidates.size() ? floor(candidates.get(i + 1).min()).subtract(BigDecimal.ONE) : null;
            var price = tier.price();
            String basis = "";
            if (legacy) {
                var untaxed = tier.base() ? untaxed(value) : price;
                basis = untaxed == null ? "含票价 " : "未税单价 ";
                if (untaxed != null) price = untaxed;
            }
            result.add(range(min, max, "件") + "；" + basis + "¥" + amount(price, legacy) + "/件");
        }
        // The legacy UI also shows an available base price when its quantity is incomplete.
        if (legacy && candidates.isEmpty()) {
            var price = number(value.path("purchasePriceCny"));
            if (price != null) {
                var untaxed = untaxed(value);
                var min = number(value.path("minOrderQty"));
                result.add(range(min == null ? BigDecimal.ONE : min, null, "件") + "；"
                    + (untaxed == null ? "含票价 " : "未税单价 ")
                    + "¥" + amount(untaxed == null ? price : untaxed, true) + "/件");
            }
        }
        return result;
    }

    private static BigDecimal untaxed(JsonNode value) {
        var source = number(value.path("sourceQuotedPriceCny"));
        if (source != null && source.signum() > 0) return source;
        var price = number(value.path("purchasePriceCny"));
        return "quoted".equals(value.path("purchasePriceBasis").asText()) && price != null && price.signum() > 0 ? price : null;
    }

    private static BigDecimal number(JsonNode value) {
        if (value.isNull() || value.isMissingNode() || value.asText().isEmpty()) return null;
        try { return new BigDecimal(value.asText().trim()); }
        catch (NumberFormatException ex) { return null; }
    }

    private static BigDecimal floor(BigDecimal value) { return value.setScale(0, RoundingMode.FLOOR).max(BigDecimal.ONE); }
    private static String range(BigDecimal min, BigDecimal max, String unit) {
        return min.stripTrailingZeros().toPlainString() + (max == null ? unit + "起" : "–" + max.stripTrailingZeros().toPlainString() + unit);
    }
    private static String amount(BigDecimal value, boolean preservePrecision) {
        // Standard/FOB cards use Number.toFixed(2), which rounds the binary double.
        // Legacy cards use decimal.js and preserve the supplier's decimal precision.
        if (!preservePrecision) return new BigDecimal(value.doubleValue()).setScale(2, RoundingMode.HALF_UP).toPlainString();
        return value.setScale(Math.max(2, value.stripTrailingZeros().scale()), RoundingMode.HALF_UP).toPlainString();
    }
    private record Tier(BigDecimal min, BigDecimal price, boolean base) {}
}
