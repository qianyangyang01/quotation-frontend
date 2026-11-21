package com.milano.quotation.logistics;

import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;

/** Exact expansion into the already-supported half-kilogram parcel tariff. */
final class YanwenJapanFirstNext {
    static final Set<String> CHANNELS=Set.of("燕文专线快递-普货","燕文专线快递-特货","燕文专线追踪-普货","燕文专线追踪-特货");
    static final BigDecimal HALF=new BigDecimal("0.5"), GRAM=new BigDecimal("0.001");
    static List<ObjectNode> expand(ObjectNode source) {
        var first=LogisticsBillingEngine.n(source,"sourceFirstWeightKg");
        var next=LogisticsBillingEngine.n(source,"sourceNextWeightKg");
        var firstPrice=LogisticsBillingEngine.n(source,"sourceFirstWeightPrice");
        var nextPrice=LogisticsBillingEngine.n(source,"sourceNextWeightPrice");
        var from=LogisticsBillingEngine.n(source,"weightFromKg");
        var to=LogisticsBillingEngine.n(source,"weightToKg");
        if(first.compareTo(HALF)!=0||next.compareTo(HALF)!=0||firstPrice.signum()<=0||nextPrice.signum()<=0
                ||to.compareTo(from)<=0||to.compareTo(new BigDecimal("30"))>0||to.remainder(HALF).signum()!=0)
            throw new IllegalArgumentException("日本首续重须明确为首重0.5kg、续重0.5kg，价格为正且上限为0.5kg整档");
        // Carrier ranges 0.001–2, 2.001–10, 10.001–20 select the half-kg charged weight.
        var lower=from.signum()==0?BigDecimal.ZERO:from.subtract(GRAM);
        if(lower.signum()<0||lower.remainder(HALF).signum()!=0||!source.path("weightToInclusive").asBoolean(true))
            throw new IllegalArgumentException("日本首续重重量段起点或终点不符合原表0.001kg衔接规则");
        var result=new ArrayList<ObjectNode>();
        for(var upper=lower.add(HALF);upper.compareTo(to)<=0;upper=upper.add(HALF)) {
            var row=source.deepCopy();
            var units=upper.subtract(HALF).divide(HALF);
            row.put("pricingModel",LogisticsPiecePricing.MODEL).put("weightFromKg",upper.subtract(HALF)).put("weightToKg",upper)
                .put("weightFromInclusive",false).put("weightToInclusive",true)
                .put("minChargeWeightKg",HALF).put("billingStepKg",HALF)
                .put("pricePerKg",0).put("intervalPrice",firstPrice.add(nextPrice.multiply(units)))
                .put("firstWeightKg",0).put("firstWeightPrice",0).put("nextWeightKg",0).put("nextWeightPrice",0)
                .put("sourcePricingModel","first-next").put("sourceNormalizationRule","yanwen-japan-first-next-500g-v1")
                .put("normalizationNote","日本首重0.5kg＋每续重0.5kg，不足0.5kg按0.5kg；按计费重量选择原表价格段后精确展开为整票价，处理费另计");
            result.add(row);
        }
        return result;
    }
}
