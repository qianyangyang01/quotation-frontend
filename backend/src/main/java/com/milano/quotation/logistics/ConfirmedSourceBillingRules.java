package com.milano.quotation.logistics;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;

/** Reviewed country/sheet rules. Applied on import, with source cells retained as evidence. */
final class ConfirmedSourceBillingRules {
    static final String VERSION = "confirmed-source-billing-2026.10.17-v1";
    private static final Set<String> FOURPX = Set.of("联邮通婚纱专线（S5290）", "联邮通经济挂号-普货（O5）",
            "联邮通标准商派专线-普货（S5246）", "联邮通服装专线（S1427）", "联邮通经济挂号-带电（JW）");
    private static final Set<String> YSD = Set.of("全球专线普货", "全球专线带电", "全球专线服装", "美国商派小包专线");
    private static final Set<String> QIAOJIE_GRAM = Set.of("巧捷小包全球特惠A(服装)", "巧捷小包全球商派E(服装)");
    private static final Set<String> SF_CLOTHING = Set.of("US", "DE", "GB", "FR", "IT", "AU");
    private static final Set<String> SF_SENSITIVE = Set.of("US", "GB", "DE", "FR", "IT", "ES", "NL", "BE", "PL", "AT", "CH", "AU");
    private record Evidence(String cell, String text) {}

    static void apply(ObjectNode channel, JsonNode sourceCells) {
        var cells = new LinkedHashMap<String, String>();
        for (var row : sourceCells) for (var cell : row.properties()) cells.put(cell.getKey(), cell.getValue().asText());
        var provider = channel.path("providerName").asText();
        for (var value : channel.path("rows")) {
            var row = (ObjectNode) value;
            if (!row.path("pricingModel").asText("per-kg").equals("per-kg")) continue;
            String sheet = row.path("sourceSheet").asText().trim(), country = row.path("countryCode").asText();
            boolean globalGram = provider.equals("递四方") && FOURPX.contains(sheet)
                    || provider.equals("云速递") && YSD.contains(sheet)
                    || provider.equals("巧捷") && QIAOJIE_GRAM.contains(sheet);
            boolean sfGram = provider.equals("顺丰") && (sheet.equals("服装专线") && SF_CLOTHING.contains(country)
                    || sheet.equals("敏货电商专递") && SF_SENSITIVE.contains(country));
            if (globalGram || sfGram) {
                for (var cell : cells.entrySet()) {
                    var text = clean(cell.getValue());
                    // Do not promote return/re-delivery increments or ambiguous “按克计费”.
                    if (text.matches("(?s).*(重派|退件|退回|销毁|退运).*") || text.contains("不按") || text.contains("不以")) continue;
                    boolean matches = globalGram && text.matches("(?:[0-9]+[、.．])?以G为单位进位[；;，,].*");
                    if (sfGram && cell.getKey().matches("C[0-9]+") && text.startsWith("单票单件计费")
                            && text.matches(".*续重按1G(?:计算计费重量|计算重量|[；;]).*")) {
                        var countryCell = cells.getOrDefault("B" + cell.getKey().substring(1), "");
                        matches = LogisticsSourceParser.minimumWeightCountries().entrySet().stream()
                                .anyMatch(c -> c.getValue().equals(country) && countryCell.contains(c.getKey()));
                    }
                    if (matches && noExplicitIncrement(row)) {
                        row.put("billingStepKg", new BigDecimal("0.001"));
                        recordStep(row, new Evidence(cell.getKey(), cell.getValue()), "gram");
                        break;
                    }
                }
            }
            if (!country.equals("CL")) continue;
            if (provider.equals("巧捷") && sheet.equals("巧捷专线小包全球特惠F(敏感)")) {
                var evidence = chileEvidence(channel, cells, sheet);
                if (evidence != null && noExplicitIncrement(row)) {
                    var bands = row.putArray("billingStepBands");
                    bands.addObject().put("aboveKg", 0).put("stepKg", 0);
                    bands.addObject().put("aboveKg", 2).put("stepKg", new BigDecimal("0.5"));
                    recordStep(row, evidence, "chile-half-kg-above-two");
                }
            }
            if (provider.equals("通邮") && sheet.equals("通邮挂号特货")) {
                var evidence = chileEvidence(channel, cells, sheet);
                // This reviewed row-specific floor supersedes only the known generic 10g note.
                // Explicit columns, manual overrides, and all other minimum conflicts stay intact.
                var minimum = row.path("minChargeWeightKg");
                if (evidence != null && minimum.isNumber() && minimum.decimalValue().compareTo(new BigDecimal("0.01")) == 0
                        && row.path("sourceMinimumWeightKind").asText().equals("note")) {
                    row.put("minChargeWeightKg", new BigDecimal("0.05"))
                            .put("sourceMinimumWeightKind", "reviewed-source")
                            .put("sourceMinimumWeightText", evidence.text())
                            .put("sourceMinimumWeightCell", evidence.cell())
                            .put("sourceMinimumWeightRuleId", VERSION + ":chile-row-minimum");
                }
            }
        }
    }

    private static Evidence chileEvidence(JsonNode channel, Map<String, String> cells, String sheet) {
        for (var row : channel.path("rows")) {
            if (!row.path("countryCode").asText().equals("CL") || !row.path("sourceSheet").asText().trim().equals(sheet)) continue;
            for (var cell : row.path("rawValues").properties()) {
                var raw = cell.getValue().asText();
                if (!raw.equals(cells.get(cell.getKey()))) continue;
                var text = clean(raw);
                if (text.equals("关税DDU，50克起重，2KG以上按照0.5进位")) return new Evidence(cell.getKey(), raw);
            }
        }
        return null;
    }
    private static boolean noExplicitIncrement(JsonNode row) {
        var step = row.path("billingStepKg");
        return (!step.isNumber() && (step.isMissingNode() || step.isNull()) || step.isNumber() && step.decimalValue().signum() == 0)
                && !row.hasNonNull("billingStepBands");
    }
    private static void recordStep(ObjectNode row, Evidence evidence, String rule) {
        row.put("sourceBillingStepRuleId", VERSION + ":" + rule)
                .put("sourceBillingStepCell", evidence.cell()).put("sourceBillingStepText", evidence.text());
    }
    private static String clean(String value) { return value.replaceAll("[\\s\\u00a0]+", "").toUpperCase(Locale.ROOT); }
}
