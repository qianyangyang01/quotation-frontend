package com.milano.quotation.logistics;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Explicit opt-in flat parcel tariff; legacy interval/first-next models remain unsupported. */
final class LogisticsPiecePricing {
    static final String MODEL="per-piece-500g";
    static final BigDecimal STEP=new BigDecimal("0.5");
    private LogisticsPiecePricing() {}
    static boolean applies(JsonNode row){return MODEL.equals(row.path("pricingModel").asText());}
    static boolean valid(JsonNode row) {
        try {
            var from=LogisticsBillingEngine.n(row,"weightFromKg");var to=LogisticsBillingEngine.n(row,"weightToKg");
            return applies(row)&&to.subtract(from).compareTo(STEP)==0&&from.remainder(STEP).signum()==0
                    &&!row.path("weightFromInclusive").asBoolean()&&row.path("weightToInclusive").asBoolean(true)
                    &&LogisticsBillingEngine.minimum(row).compareTo(STEP)==0
                    &&LogisticsBillingEngine.n(row,"intervalPrice").signum()>0
                    &&java.util.List.of("pricePerKg","firstWeightKg","firstWeightPrice","nextWeightKg","nextWeightPrice","surcharge")
                        .stream().allMatch(key->LogisticsBillingEngine.n(row,key).signum()==0);
        } catch(RuntimeException invalid){return false;}
    }
    static BigDecimal charged(BigDecimal weight){return weight.max(STEP).divide(STEP,0,RoundingMode.CEILING).multiply(STEP);}
}
