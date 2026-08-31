package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Fail-closed evaluator for explicitly supported, fully specified price rules. */
@Component
public class LogisticsBillingEngine {
    public static final String VERSION="logistics-billing-v1";
    private final ObjectMapper mapper;
    public LogisticsBillingEngine(ObjectMapper mapper){this.mapper=mapper;}

    public List<String> unsupported(JsonNode rows) {
        var reasons=new LinkedHashSet<String>();
        if(!rows.isArray()||rows.isEmpty())reasons.add("没有完整价格行");
        for(var row:rows){
            long baseTypes=List.of("pricePerKg","intervalPrice","firstWeightPrice").stream().filter(f->n(row,f).signum()>0).count();
            if(baseTypes!=1)reasons.add("基础计费价格必须唯一，不能同时使用公斤价、区间价和首重价");
            if(!row.path("pendingReason").asText().isBlank())reasons.add(row.path("pendingReason").asText());
            if(!row.path("notes").asText().isBlank())reasons.add("规则原文尚未转换为经过核验的结构化计费条件");
            if(!row.path("currency").asText("CNY").equals("CNY"))reasons.add("非人民币计价未适配");
            if(!row.path("zoneName").asText().isBlank())reasons.add("分区邮编与选区规则尚未核验");
            if(!row.path("originRegion").asText().isBlank())reasons.add("发货区域规则尚未核验");
            if(!Set.of("per-kg","first-next","interval").contains(model(row)))reasons.add("未知计费方式");
            if(!row.path("volumetric").isBoolean())reasons.add("必须明确是否计泡");
            if(row.path("volumetric").asBoolean()&&n(row,"volumeDivisor").signum()<=0)reasons.add("缺少明确计泡系数，禁止默认8000");
            for(var field:List.of("fuelSurchargeRate","linehaulPerKg","billingStepKg","minLengthCm","maxLengthCm","minWidthCm","maxWidthCm","minSideAreaCm2","maxSideAreaCm2","maxPerimeterCm","maxSideCm"))
                if(n(row,field).signum()>0)reasons.add("暂未支持字段："+field);
            for(var field:List.of("zonePostalPrefix","zonePostalCode","zoneCity","zoneState","specialGoodsContent"))if(!row.path(field).asText().isBlank())reasons.add("暂未支持条件："+field);
            if(row.path("zoneExclude").asBoolean()||row.path("phoneRequired").asBoolean())reasons.add("邮区排除或电话准入尚未接入");
            if(n(row,"weightToKg").compareTo(n(row,"weightFromKg"))<=0)reasons.add("重量范围无效");
            if(model(row).equals("per-kg")&&n(row,"pricePerKg").signum()<=0)reasons.add("公斤价格无效");
            if(model(row).equals("interval")&&n(row,"intervalPrice").signum()<=0)reasons.add("区间价格无效");
            if(model(row).equals("first-next")&&(n(row,"firstWeightKg").signum()<=0||n(row,"firstWeightPrice").signum()<=0||n(row,"nextWeightKg").signum()<=0))reasons.add("首续重参数不完整");
        }
        return List.copyOf(reasons);
    }

    public ObjectNode calculate(JsonNode rows,JsonNode input) {
        var reasons=unsupported(rows);if(!reasons.isEmpty())throw AppException.unprocessable("计费未适配："+String.join("；",reasons));
        var weight=positive(input,"weightKg");var country=input.path("country").asText();
        if(country.isBlank())throw AppException.unprocessable("缺少报价国家");
        var marks=new HashSet<String>();for(var mark:input.path("marks"))marks.add(mark.asText());
        if(marks.isEmpty())throw AppException.unprocessable("缺少货物属性");
        var matches=new ArrayList<ObjectNode>();int index=0;
        for(var row:rows){int current=index++;
            if(!row.path("countryCode").asText().equalsIgnoreCase(country)&&!row.path("areaName").asText().equals(country))continue;
            if(!eligible(row,marks))continue;
            BigDecimal charge=weight,volume=BigDecimal.ZERO;
            var dims=input.path("dimensions");
            if(row.path("volumetric").asBoolean()){
                if(dims.has("volumeMultiplier")&&positive(dims,"volumeMultiplier").compareTo(BigDecimal.ONE)<0)throw AppException.unprocessable("体积倍数不能小于1");
                volume=positive(dims,"lengthCm").multiply(positive(dims,"widthCm")).multiply(positive(dims,"heightCm"))
                        .multiply(dims.has("volumeMultiplier")?positive(dims,"volumeMultiplier"):BigDecimal.ONE)
                        .divide(n(row,"volumeDivisor"),12,RoundingMode.HALF_UP);charge=charge.max(volume);
            }
            if(!includes(row,charge))continue;
            BigDecimal base;
            switch(model(row)){
                case "interval" -> base=n(row,"intervalPrice");
                case "first-next" -> base=n(row,"firstWeightPrice").add(charge.subtract(n(row,"firstWeightKg")).max(BigDecimal.ZERO)
                        .divide(n(row,"nextWeightKg"),0,RoundingMode.CEILING).multiply(n(row,"nextWeightPrice")));
                default -> base=charge.max(n(row,"minChargeWeightKg")).max(n(row,"startWeightKg")).multiply(n(row,"pricePerKg"));
            }
            matches.add(mapper.createObjectNode().put("rowIndex",current).put("base",base).put("chargeWeightKg",charge).put("volumeWeightKg",volume)
                    .put("total",base.add(n(row,"registrationFee")).add(n(row,"surcharge")).setScale(2,RoundingMode.HALF_UP)).put("engineVersion",VERSION));
        }
        if(matches.size()!=1)throw AppException.unprocessable(matches.isEmpty()?"国家、重量或货物属性不在已核验范围":"存在多个匹配价格，禁止自动选择最低价");
        return matches.getFirst();
    }
    static boolean includes(JsonNode r,BigDecimal w){int lo=w.compareTo(n(r,"weightFromKg")),hi=w.compareTo(n(r,"weightToKg"));return (lo>0||lo==0&&r.path("weightFromInclusive").asBoolean())&&(hi<0||hi==0&&r.path("weightToInclusive").asBoolean(true));}
    static String model(JsonNode r){return r.path("pricingModel").asText(n(r,"intervalPrice").signum()>0?"interval":n(r,"firstWeightPrice").signum()>0?"first-next":"per-kg");}
    static BigDecimal n(JsonNode r,String key){var v=r.path(key);if(v.isMissingNode()||v.isNull())return BigDecimal.ZERO;if(!v.isNumber()||!Double.isFinite(v.asDouble())||v.decimalValue().signum()<0)throw AppException.unprocessable("非有效非负数："+key);return v.decimalValue();}
    static BigDecimal positive(JsonNode r,String key){var v=n(r,key);if(v.signum()<=0)throw AppException.unprocessable("缺少有效参数："+key);return v;}
    static boolean eligible(JsonNode r,Set<String> marks){var prohibited=split(r.path("prohibitedMarks").asText());var allowed=split(r.path("allowedMarks").asText());return marks.stream().noneMatch(prohibited::contains)&&!(r.path("prohibitGeneralCargo").asBoolean()&&marks.contains("普货"))&&(allowed.isEmpty()||marks.stream().allMatch(m->m.equals("普货")||allowed.contains(m)));}
    static Set<String> split(String s){return new HashSet<>(Arrays.asList(s.split("[,，、;；|]")));}
}
