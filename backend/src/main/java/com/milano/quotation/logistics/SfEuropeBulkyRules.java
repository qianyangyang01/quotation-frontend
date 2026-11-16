package com.milano.quotation.logistics;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;

/** Exact SF bulky sheet only. Source footer is evidence, never an instruction. */
final class SfEuropeBulkyRules {
    static final String NAME="欧洲-泡货专线";
    static final Set<String> EU=Set.of("DE","FR","IT","ES","PL","CZ","AT","BE","LU","NL","DK","HU","SK","SI","IE","PT","SE","EE","FI","HR","LT","LV","BG","GR","RO");
    private static String clean(String s){return s.replaceAll("[\\s\\u00a0]+","");}
    static void apply(ObjectNode channel,JsonNode sourceCells){
        if(!channel.path("providerName").asText().equals("顺丰")||!channel.path("channelName").asText().equals(NAME))return;
        var cells=new LinkedHashMap<String,String>();
        for(var record:sourceCells)for(var c:record.properties())cells.put(c.getKey(),c.getValue().asText());
        for(var value:channel.path("rows")){
            var row=(ObjectNode)value;var country=row.path("countryCode").asText();
            if(!row.path("sourceSheet").asText().equals(NAME))continue;
            var matches=new ArrayList<String>();
            for(var c:cells.entrySet())if(c.getKey().matches("C[0-9]+")){
                var group=clean(cells.getOrDefault("B"+c.getKey().substring(1),""));
                if(!cells.getOrDefault("F"+c.getKey().substring(1),"").contains("/")&&!c.getValue().contains("运费"))continue;
                if(country.equals("GB")&&group.equals("英国")||EU.contains(country)&&group.equals("欧盟25国"))matches.add(c.getKey());
            }
            if(matches.size()!=1){LogisticsReadiness.block(row,"泡货专线缺少唯一的国家计费说明，需核对");continue;}
            var cell=matches.getFirst();var text=clean(cells.get(cell));
            var volume=clean(cells.getOrDefault("F"+cell.substring(1),""));
            if(!text.equals("本渠道仅支持一票一件；运费精确至克，体积重按/18000体积重计算，与实重相比较，取大值计算运费。")
                    ||!volume.equals("长*宽*高（cm）/18000")){
                LogisticsReadiness.block(row,"泡货专线克重或材积说明变化，需核对后报价");continue;
            }
            if(!row.path("pricingModel").asText().equals("per-kg")||row.hasNonNull("billingStepBands")
                    ||row.path("billingStepKg").asDouble()!=0&&row.path("billingStepKg").asDouble()!=.001){
                LogisticsReadiness.block(row,"泡货专线计费进位冲突，需核对");continue;
            }
            row.put("billingStepKg",new BigDecimal("0.001")).put("volumeDivisor",18000).put("volumetric",true)
                    .put("sourceBillingStepCell",cell).put("sourceBillingStepText",cells.get(cell))
                    .put("sourceBillingStepRuleId","sf-europe-bulky-2026.11.16:gram")
                    .put("sourceVolumeDivisorCell","F"+cell.substring(1));
            LogisticsReadiness.warn(row,"一票一件；需按实重与长×宽×高(cm)÷18000取大后的计费重试算；尺寸、偏远邮编及额外费用需人工核对");
        }
    }
}
