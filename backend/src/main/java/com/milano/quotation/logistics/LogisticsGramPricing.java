package com.milano.quotation.logistics;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Explicit per-kilogram tariff rounded upwards to one gram, after the source minimum. */
final class LogisticsGramPricing {
    static final String MODEL="per-kg-1g";
    private LogisticsGramPricing() {}
    static boolean applies(JsonNode row){return MODEL.equals(row.path("pricingModel").asText());}
    static BigDecimal charged(BigDecimal weight){return weight.setScale(3,RoundingMode.CEILING);}
}
