package com.milano.quotation.logistics;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Confirmed source rule only: YunExpress cosmetics to Kuwait rounds up by 100 g. */
final class LogisticsKuwaitCosmeticsPricing {
    private static final BigDecimal STEP = new BigDecimal("0.1");
    private LogisticsKuwaitCosmeticsPricing() {}

    static boolean applies(JsonNode row) {
        return row.path("pricingModel").asText("per-kg").equals("per-kg")
                && row.path("sourceSheet").asText().equals("云途全球化妆品类专线挂号")
                && row.path("countryCode").asText().equals("KW")
                && row.path("billingStepKg").isNumber()
                && row.path("billingStepKg").decimalValue().compareTo(STEP) == 0;
    }

    static BigDecimal charged(BigDecimal weight) {
        return weight.divide(STEP, 0, RoundingMode.CEILING).multiply(STEP);
    }
}
