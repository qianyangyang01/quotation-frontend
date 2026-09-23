package com.milano.quotation.logistics;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Explicit kilogram increments, applied after the minimum and before tariff selection. */
final class LogisticsStepPricing {
    private LogisticsStepPricing() {}

    static boolean supported(JsonNode row) {
        var step = row.path("billingStepKg");
        if (!step.isMissingNode() && !step.isNull()
                && (!step.isNumber() || !Double.isFinite(step.asDouble()) || step.decimalValue().signum() < 0)) return false;
        var bands = row.path("billingStepBands");
        var model = row.path("pricingModel").asText("per-kg");
        if (model.equals("per-kg-1g") || model.equals("per-piece-500g")) {
            if (!bands.isMissingNode() && !bands.isNull()) return false;
            var nativeStep = new BigDecimal(model.equals("per-kg-1g") ? "0.001" : "0.5");
            if (step.isNumber() && step.decimalValue().signum() > 0 && step.decimalValue().compareTo(nativeStep) != 0) return false;
        }
        if (!bands.isMissingNode() && !bands.isNull()) {
            if (!bands.isArray() || bands.isEmpty()) return false;
            BigDecimal previous = null;
            for (var band : bands) {
                var above = band.path("aboveKg"); var unit = band.path("stepKg");
                if (!above.isNumber() || !unit.isNumber() || !Double.isFinite(above.asDouble()) || !Double.isFinite(unit.asDouble())
                        || above.decimalValue().signum() < 0 || unit.decimalValue().signum() < 0
                        || previous == null && above.decimalValue().signum() != 0
                        || previous != null && above.decimalValue().compareTo(previous) <= 0) return false;
                previous = above.decimalValue();
            }
            if (step.isNumber() && step.decimalValue().signum() > 0) return false;
        }
        return true;
    }

    static BigDecimal step(JsonNode row, BigDecimal weight) {
        var result = LogisticsBillingEngine.n(row, "billingStepKg");
        for (var band : row.path("billingStepBands")) {
            if (weight.compareTo(band.path("aboveKg").decimalValue()) > 0) result = band.path("stepKg").decimalValue();
        }
        return result;
    }

    static BigDecimal charged(JsonNode row, BigDecimal weight) {
        var step = step(row, weight);
        return step.signum() > 0 ? weight.divide(step, 0, RoundingMode.CEILING).multiply(step) : weight;
    }

    static String key(JsonNode row) {
        return LogisticsBillingEngine.n(row,"billingStepKg").stripTrailingZeros().toPlainString()
                + "|" + row.path("billingStepBands").toString();
    }

    static String exportBands(JsonNode row) {
        var parts = new java.util.ArrayList<String>();
        for (var band : row.path("billingStepBands")) parts.add(band.path("aboveKg").decimalValue().stripTrailingZeros().toPlainString()
                + "以上:" + band.path("stepKg").decimalValue().stripTrailingZeros().toPlainString());
        return String.join(";", parts);
    }

    static JsonNode importBands(String text) {
        var bands = tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (var part : text.split("[;；]")) {
            var pair = part.trim().split("以上[:：]", -1);
            if (pair.length != 2) throw new IllegalArgumentException("分段进位格式应为0以上:0.01;2以上:0.5，单位kg");
            bands.addObject().put("aboveKg", new BigDecimal(pair[0].trim())).put("stepKg", new BigDecimal(pair[1].trim()));
        }
        var row = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().set("billingStepBands", bands);
        if (!supported(row)) throw new IllegalArgumentException("分段进位须从0开始，阈值递增，进位重量不得为负");
        return bands;
    }
}
