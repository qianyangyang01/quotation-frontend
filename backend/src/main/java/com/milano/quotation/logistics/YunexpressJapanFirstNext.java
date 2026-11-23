package com.milano.quotation.logistics;

import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;

/** Only the supplier's reviewed BKPHR Japan two-band, half-kilogram contract. */
final class YunexpressJapanFirstNext {
    static final String NAME="云途全球专线挂号（标快普货）",CODE="BKPHR";
    static final BigDecimal HALF=new BigDecimal("0.5");
    static List<ObjectNode> expand(ObjectNode source) {
        var first=LogisticsBillingEngine.n(source,"sourceFirstWeightKg");
        var next=LogisticsBillingEngine.n(source,"sourceNextWeightKg");
        var firstPrice=LogisticsBillingEngine.n(source,"sourceFirstWeightPrice");
        var nextPrice=LogisticsBillingEngine.n(source,"sourceNextWeightPrice");
        var from=LogisticsBillingEngine.n(source,"weightFromKg");var to=LogisticsBillingEngine.n(source,"weightToKg");
        if(first.compareTo(HALF)!=0||next.compareTo(HALF)!=0||firstPrice.signum()<=0||nextPrice.signum()<=0)
            throw new IllegalArgumentException("云途日本须明确首重500克、续重500克，首续重价格须为正数");
        if(!((from.signum()==0&&to.compareTo(new BigDecimal("2"))==0)
                ||(from.compareTo(new BigDecimal("2"))==0&&to.compareTo(new BigDecimal("5"))==0))
                ||source.path("weightFromInclusive").asBoolean()||!source.path("weightToInclusive").asBoolean())
            throw new IllegalArgumentException("云途日本已核验范围为0<W≤2kg、2<W≤5kg；重量段变化须重新核对");
        var result=new ArrayList<ObjectNode>();
        for(var upper=from.add(HALF);upper.compareTo(to)<=0;upper=upper.add(HALF)) {
            var row=source.deepCopy();
            row.put("pricingModel",LogisticsPiecePricing.MODEL).put("weightFromKg",upper.subtract(HALF)).put("weightToKg",upper)
                .put("weightFromInclusive",false).put("weightToInclusive",true).put("minChargeWeightKg",HALF).put("billingStepKg",HALF)
                .put("pricePerKg",0).put("registrationFee",0).put("intervalPrice",firstPrice.add(nextPrice.multiply(upper.subtract(HALF).divide(HALF))))
                .put("volumetric",false).put("volumeDivisor",0)
                .put("firstWeightKg",0).put("firstWeightPrice",0).put("nextWeightKg",0).put("nextWeightPrice",0)
                .put("sourcePricingModel","first-next").put("sourceNormalizationRule","yunexpress-BKPHR-japan-first-next-500g-v1")
                .put("normalizationNote","云途BKPHR日本：首重500克、续重500克，不足500克向上进位；按整票计费重量所属原表价格段计算，保价/签名服务费不作为续重或挂号费");
            result.add(row);
        }
        return result;
    }
}
