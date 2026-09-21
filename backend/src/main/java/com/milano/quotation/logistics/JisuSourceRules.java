package com.milano.quotation.logistics;

import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Approved source product identity; prices and tier counts always come from the workbook. */
final class JisuSourceRules {
    static final String PROVIDER="急速国际";
    static final String CHANNEL="急速-美国化妆品专线（商派）";
    static final String CODE="JS02-邮编敏感货-2";
    private JisuSourceRules() {}

    static boolean providerEvidence(String text) {
        return text.contains(PROVIDER)||text.contains("急速化妆品专线")
                ||text.matches("(?i)^JS[-_－].*\\.xlsx?$");
    }
    static boolean allowed(String country,String code) {
        return country.equalsIgnoreCase("US")&&CompanyChannelScope.normalize(CODE).equals(CompanyChannelScope.normalize(code));
    }
    static boolean allowedStandard(String country,String name,String code) {
        return allowed(country,code)||(country.equalsIgnoreCase("US")&&code.isBlank()
                &&CompanyChannelScope.normalize(CHANNEL).equals(CompanyChannelScope.normalize(name)));
    }
    static void apply(ObjectNode channel) {
        var footer=channel.path("sourceNotes").asText();
        var volume=Pattern.compile("(?i)(?:长[*×]宽[*×]高|L[*×]W[*×]H)(?:cm)?/(\\d+)")
                .matcher(footer.replaceAll("\\s+",""));
        Integer divisor=volume.find()?Integer.valueOf(volume.group(1)):null;
        for(var value:channel.path("rows")) {
            var row=(ObjectNode)value;
            if(divisor!=null&&divisor>0)row.put("volumeDivisor",divisor).put("volumetric",true);
        }
    }

    static void applyMinimum(ObjectNode channel) {
        // Raw source rows only. Standard templates retain their explicitly reviewed billing fields.
        ObjectNode first=null;
        for(var value:channel.path("rows")) {
            var row=(ObjectNode)value;
            if(!allowed(row.path("countryCode").asText(),row.path("sourceProductCode").asText())||!row.has("sourceWeightCell"))continue;
            if(first==null||row.path("weightFromKg").decimalValue().compareTo(first.path("weightFromKg").decimalValue())<0)first=row;
        }
        if(first==null)return;
        var from=first.path("weightFromKg").decimalValue();
        var minimum=first.path("minChargeWeightKg").isNumber()?first.path("minChargeWeightKg").decimalValue():BigDecimal.ZERO;
        boolean explicit=minimum.signum()>0;
        if(!explicit)minimum=new BigDecimal("0.05");
        // A later workbook changing the first boundary requires an explicit minimum, not a guess.
        if(minimum.compareTo(from)<0||!explicit&&from.compareTo(minimum)!=0) {
            for(var value:channel.path("rows"))LogisticsReadiness.block((ObjectNode)value,"急速最低计费重量与首档边界发生变化，需核对");
            return;
        }
        for(var value:channel.path("rows")) {
            var row=(ObjectNode)value;
            if(!allowed(row.path("countryCode").asText(),row.path("sourceProductCode").asText()))continue;
            if(row.path("minChargeWeightKg").asDouble()==0)row.put("minChargeWeightKg",minimum)
                    .put("sourceMinimumWeightKind",explicit?"column-inherited":"confirmed-provider-minimum")
                    .put("sourceMinimumWeightText",explicit?first.path("sourceMinimumWeightText").asText():"用户确认：50g起重，不足50g补至50g，50g进入首档")
                    .put("sourceMinimumWeightCell",first.path("sourceWeightCell").asText());
            if(row.path("weightFromKg").decimalValue().compareTo(minimum)==0)row.put("weightFromInclusive",true)
                    .put("normalizationNote","按已确认起重规则：最低计费重量包含在首档，原始重量表达式保留");
        }
    }
}
