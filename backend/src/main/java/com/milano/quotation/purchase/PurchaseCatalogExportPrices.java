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

    static List<BigDecimal> tiers(JsonNode value, boolean fob) {
        var result = new ArrayList<BigDecimal>();
        if (fob) {
            for (var tier : value.path("fobTiers")) {
                var min = number(tier.path("minQty"));
                var price = number(tier.path("unitPriceCny"));
                result.add(min == null || price == null ? null : amount(price, false));
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
        for (var tier : candidates) {
            var price = tier.price();
            if (legacy) {
                var untaxed = tier.base() ? untaxed(value) : price;
                if (untaxed != null) price = untaxed;
            }
            result.add(amount(price, legacy));
        }
        // The legacy UI also shows an available base price when its quantity is incomplete.
        if (legacy && candidates.isEmpty()) {
            var price = number(value.path("purchasePriceCny"));
            if (price != null) {
                var untaxed = untaxed(value);
                result.add(amount(untaxed == null ? price : untaxed, true));
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

    private static BigDecimal amount(BigDecimal value, boolean preservePrecision) {
        // Standard/FOB cards use Number.toFixed(2), which rounds the binary double.
        // Legacy cards use decimal.js and preserve the supplier's decimal precision.
        if (!preservePrecision) return new BigDecimal(value.doubleValue()).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return value.stripTrailingZeros();
    }
    private record Tier(BigDecimal min, BigDecimal price, boolean base) {}
}
