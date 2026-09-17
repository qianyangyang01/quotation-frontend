package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import com.milano.quotation.common.EuropeanUnion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/** Recompute EU duty and the opt-in member-country fee independently on save. */
final class EuHandlingTax {
    private EuHandlingTax() {}
    static boolean validateQuote(JsonNode settings, JsonNode option, JsonNode exchange, int customQuantity) {
        var destination = option.path("country").asText();
        if (!EuropeanUnion.contains(destination)) return false;
        JsonNode local = null, eu = null;
        for (var row : settings.path("countries")) {
            if (!row.path("selected").asBoolean()) continue;
            if (local == null && EuropeanUnion.sameCountry(row.path("country").asText(), destination)) local = row;
            if (eu == null && EuropeanUnion.TAX_GROUP.equals(row.path("country").asText())) eu = row;
        }
        if (local == null || !"add-handling".equals(local.path("euTaxMode").asText())) return false;
        if (eu == null) changed();
        var key = option.path("channelKey").asText();
        var euRule = find(eu.path("channelRules"), key);
        var handling = find(local.path("handlingRules"), key);
        var parts = key.split("::", 3);
        var provider = parts.length == 3 ? parts[1].trim() : "";
        boolean chc = parts.length == 3 && (provider.equals("云途") || provider.equalsIgnoreCase("YunExpress"))
            && Set.of("C-600c364a09421e97a32f", "C-f79790fa71c225481346", "C-12d8524ab88e7866389a").contains(parts[2]);
        if (euRule == null && chc) {
            var formula = (ObjectNode) eu.deepCopy(); formula.removeAll();
            formula.put("mode", "weight").put("amount", new BigDecimal("0.6")).put("perKg", new BigDecimal("1.5")).put("currency", "EUR");
            euRule = formula;
        }
        boolean weighted = isWeight(euRule) || isWeight(handling);
        if (!option.path("taxConfigured").asBoolean() || option.path("taxIncluded").asBoolean()
            || !(weighted ? "weight-order" : "fixed-order").equals(option.path("taxFeeMode").asText())) changed();
        var fixed = weighted ? BigDecimal.ZERO : euAmount(euRule, eu, settings, provider, BigDecimal.ZERO, exchange).add(amount(handling, BigDecimal.ZERO, exchange));
        equal(option.path("countryFixedTaxUsd"), fixed);
        var snapshots = option.path("taxCalculations");
        if (!snapshots.isObject() || snapshots.isEmpty()) changed();
        for (var entry : snapshots.properties()) {
            var snapshot = entry.getValue();
            if (!"eu-handling-v1".equals(snapshot.path("rule").asText())
                || !local.path("country").asText().equals(snapshot.path("country").asText())
                || !key.equals(snapshot.path("channelKey").asText())) changed();
            if (!snapshot.path("weightKg").isNumber()) changed();
            var weight = snapshot.path("weightKg").decimalValue();
            if (weighted) {
                if (weight.signum() <= 0) changed();
                boolean matched = false;
                for (var sample : option.path("logisticsSamples")) if (entry.getKey().equals(sample.path("quantity").asText())
                    && sample.path("total").isNumber() && sample.path("input").path("weightKg").isNumber()
                    && sample.path("input").path("weightKg").decimalValue().compareTo(weight) == 0) matched = true;
                if (!matched) changed();
            }
            var duty = euAmount(euRule, eu, settings, provider, weight, exchange);
            var fee = amount(handling, weight, exchange);
            equal(snapshot.path("euTaxUsd"), duty);
            equal(snapshot.path("handlingFeeUsd"), fee);
            equal(snapshot.path("taxUsd"), duty.add(fee));
        }
        for (var field : Set.of("tax1Usd", "tax2Usd", "tax3Usd", "taxCustomUsd")) {
            if (!option.hasNonNull(field)) continue;
            var quantity = field.equals("taxCustomUsd") ? String.valueOf(customQuantity) : field.substring(3, 4);
            var snapshot = snapshots.path(quantity);
            if (!snapshot.path("taxUsd").isNumber()) changed();
            equal(option.path(field), snapshot.path("taxUsd").decimalValue());
        }
        return true;
    }
    private static JsonNode find(JsonNode rules, String key) {
        for (var row : rules) if (key.equals(row.path("key").asText())) return row;
        return null;
    }
    private static boolean isWeight(JsonNode rule) { return rule != null && "weight".equals(rule.path("mode").asText()); }
    private static BigDecimal amount(JsonNode rule, BigDecimal weight, JsonNode exchange) {
        if (rule == null) return BigDecimal.ZERO;
        if ("unavailable".equals(rule.path("mode").asText())) changed();
        return ChannelTaxRules.amount(rule, weight, exchange);
    }
    private static BigDecimal euAmount(JsonNode rule, JsonNode eu, JsonNode settings, String provider, BigDecimal weight, JsonNode exchange) {
        if (rule != null) return amount(rule, weight, exchange);
        if (!eu.path("enabled").asBoolean() || eu.path("fixedFeeUsd").asDouble() <= 0) return BigDecimal.ZERO;
        for (var row : eu.path("providers").isArray() ? eu.path("providers") : settings.path("providers")) {
            if (row.path("selected").asBoolean() && provider.equals(row.path("provider").asText().trim()))
                return "exempt".equals(row.path("mode").asText()) ? BigDecimal.ZERO : eu.path("fixedFeeUsd").decimalValue().setScale(2, RoundingMode.HALF_UP);
        }
        changed(); return BigDecimal.ZERO;
    }
    private static void equal(JsonNode value, BigDecimal expected) {
        if (!value.isNumber() || value.decimalValue().compareTo(expected) != 0) changed();
    }
    private static void changed() { throw AppException.conflict("欧盟关税、处理费或汇率已变化，请重新计价后提交"); }
}
