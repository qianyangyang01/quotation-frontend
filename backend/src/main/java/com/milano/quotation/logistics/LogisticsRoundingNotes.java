package com.milano.quotation.logistics;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reviewed source rules only. Country scope is explicit; copied sheet notes are not global rules. */
public final class LogisticsRoundingNotes {
    public static final String RESOURCE = "/logistics-rules/rounding-notes-20260923.json";
    private static final JsonNode RULES = new ObjectMapper().readTree(resource());
    private LogisticsRoundingNotes() {}

    public static byte[] resource() {
        try (var input = LogisticsRoundingNotes.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing reviewed rounding rules");
            return input.readAllBytes();
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    static JsonNode apply(JsonNode source) {
        if (!source.path("pricingModel").asText("per-kg").equals("per-kg")
                || source.hasNonNull("billingStepBands") || source.path("billingStepKg").asDouble() != 0) return source;
        for (var rule : RULES) {
            if (!contains(rule.path("sheets"), source.path("sourceSheet").asText().trim())) continue;
            if (!source.path("sourceFile").asText().toLowerCase(java.util.Locale.ROOT).contains(rule.path("fileContains").asText().toLowerCase(java.util.Locale.ROOT))) continue;
            var country = source.path("countryCode").asText();
            if (rule.has("countries") && !contains(rule.path("countries"), country)) continue;
            if (contains(rule.path("excludeCountries"), country)) continue;
            if (!source.path("notes").asText().contains(rule.path("note").asText())) continue;
            if (rule.has("rawContains") && !source.path("rawValues").toString().contains(rule.path("rawContains").asText())) continue;
            var result = (ObjectNode) source.deepCopy();
            result.set("billingStepBands", rule.path("bands").deepCopy());
            return result;
        }
        return source;
    }

    private static boolean contains(JsonNode values, String expected) {
        for (var value : values) if (value.asText().equals(expected)) return true;
        return false;
    }
}
