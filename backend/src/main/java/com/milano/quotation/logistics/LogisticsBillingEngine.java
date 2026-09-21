package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import com.milano.quotation.common.CountryIdentity;
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
    public static final String VERSION="logistics-billing-v7";
    private final ObjectMapper mapper;
    public LogisticsBillingEngine(ObjectMapper mapper){this.mapper=mapper;}
    static BigDecimal minimum(JsonNode row){return n(row,"minChargeWeightKg").max(n(row,"startWeightKg"));}

    public List<String> unsupported(JsonNode rows) {
        var reasons=new LinkedHashSet<String>();
        var routePrices=new LinkedHashMap<String,Set<String>>();
        if(!rows.isArray()||rows.isEmpty())reasons.add("没有完整价格行");
        for(var row:rows){
            if(row.path("pendingReason").asText().matches("(?s).*(最低|最小)计费重量.*"))reasons.add("最低计费重量尚未核对");
            boolean piece=LogisticsPiecePricing.applies(row);
            if((!model(row).equals("per-kg")&&!LogisticsGramPricing.applies(row)&&!piece)||List.of("firstWeightKg","firstWeightPrice","nextWeightKg","nextWeightPrice").stream().anyMatch(f->n(row,f).signum()>0)||!piece&&n(row,"intervalPrice").signum()>0)
                reasons.add("当前仅支持重量段公斤价加每票费，首重续重和区间一口价不参与报价");
            if(piece&&!LogisticsPiecePricing.valid(row))reasons.add("0.5kg进位整票价的档位、起重或金额无效");
            if(n(row,"surcharge").signum()>0)reasons.add("当前仅支持公斤价和每票费，附加费尚未接入报价");
            long baseTypes=List.of("pricePerKg","intervalPrice","firstWeightPrice").stream().filter(f->n(row,f).signum()>0).count();
            if(baseTypes!=1)reasons.add("基础计费价格必须唯一，不能同时使用公斤价、区间价和首重价");
            if(!row.path("currency").asText("CNY").equals("CNY"))reasons.add("非人民币计价未适配");
            if(!Set.of("per-kg","first-next","interval",LogisticsPiecePricing.MODEL,LogisticsGramPricing.MODEL).contains(model(row)))reasons.add("未知计费方式");
            if(n(row,"weightToKg").compareTo(n(row,"weightFromKg"))<=0)reasons.add("重量范围无效");
            var minimum=minimum(row);
            int minimumToUpper=minimum.compareTo(n(row,"weightToKg"));
            if(minimum.signum()>0&&(minimumToUpper>0||minimumToUpper==0&&!row.path("weightToInclusive").asBoolean(true)))reasons.add("最小计费重量超出重量段上限");
            if((model(row).equals("per-kg")||LogisticsGramPricing.applies(row))&&n(row,"pricePerKg").signum()<=0)reasons.add("公斤价格无效");
            if(model(row).equals("interval")&&n(row,"intervalPrice").signum()<=0)reasons.add("区间价格无效");
            if(model(row).equals("first-next")&&(n(row,"firstWeightKg").signum()<=0||n(row,"firstWeightPrice").signum()<=0||n(row,"nextWeightKg").signum()<=0))reasons.add("首续重参数不完整");
            var route=routeKey(row);var price=priceKey(row);
            routePrices.computeIfAbsent(route,ignored->new LinkedHashSet<>()).add(price);
        }
        if(routePrices.values().stream().anyMatch(prices->prices.size()>1))reasons.add("同一国家、重量和分区存在多套价格，需要明确报价区域");
        return List.copyOf(reasons);
    }

    public ObjectNode calculate(JsonNode rows,JsonNode input) {
        var reasons=unsupported(rows);if(!reasons.isEmpty())throw AppException.unprocessable("计费未适配："+String.join("；",reasons));
        var weight=positive(input,"weightKg");var country=input.path("country").asText();
        if(country.isBlank())throw AppException.unprocessable("缺少报价国家");
        var countryRows=new ArrayList<JsonNode>();
        for(var row:rows)if(CountryIdentity.matches(row.path("countryCode").asText(),row.path("areaName").asText(),country))countryRows.add(row);
        var zones=zoneOptions(countryRows);var requestedZone=input.path("zoneName").asText(input.path("quoteRegion").asText());
        if(!zones.isEmpty()&&requestedZone.isBlank())throw AppException.unprocessable("该国家存在分区价格，请明确选择分区");
        var matches=new ArrayList<ObjectNode>();int index=0;
        for(var row:rows){int current=index++;
            if(!CountryIdentity.matches(row.path("countryCode").asText(),row.path("areaName").asText(),country))continue;
            if(!available(row))continue;
            if(!zones.isEmpty()&&!zoneMatches(row.path("zoneName").asText(),requestedZone))continue;
            BigDecimal minimum=minimum(row),charge=weight.max(minimum),volume=BigDecimal.ZERO;
            if(LogisticsPiecePricing.applies(row))charge=LogisticsPiecePricing.charged(charge);
            else if(LogisticsGramPricing.applies(row))charge=LogisticsGramPricing.charged(charge);
            if(!includes(row,charge))continue;
            BigDecimal base=LogisticsPiecePricing.applies(row)?n(row,"intervalPrice"):charge.multiply(n(row,"pricePerKg"));
            matches.add(mapper.createObjectNode().put("rowIndex",current).put("base",base).put("actualWeightKg",weight).put("minChargeWeightKg",minimum).put("chargeWeightKg",charge).put("volumeWeightKg",volume)
                    .put("total",base.add(n(row,"registrationFee")).setScale(2,RoundingMode.HALF_UP)).put("engineVersion",VERSION));
        }
        if(matches.isEmpty())throw AppException.unprocessable("国家、重量或货物属性不在已核验范围");
        if(matches.size()>1){var totals=new HashSet<String>();for(var match:matches)totals.add(match.path("total").decimalValue().stripTrailingZeros().toPlainString());if(totals.size()>1)throw AppException.unprocessable("存在多个匹配价格，禁止自动选择最低价");}
        return matches.getFirst();
    }
    static boolean includes(JsonNode r,BigDecimal w){int lo=w.compareTo(n(r,"weightFromKg")),hi=w.compareTo(n(r,"weightToKg"));return (lo>0||lo==0&&r.path("weightFromInclusive").asBoolean())&&(hi<0||hi==0&&r.path("weightToInclusive").asBoolean(true));}
    static Set<String> zoneOptions(Collection<JsonNode> rows){var zones=new LinkedHashSet<String>();boolean unzoned=false;for(var row:rows){var value=row.path("zoneName").asText().trim();if(value.isBlank())unzoned=true;else zones.addAll(splitZones(value));}return zones.size()>1||unzoned&&!zones.isEmpty()?zones:Set.of();}
    static boolean zoneMatches(String rowZone,String requested){var wanted=normalizeZone(requested);if(wanted.equals("全国统一"))return rowZone.isBlank()||normalizeZone(rowZone).equals(wanted);return splitZones(rowZone).stream().map(LogisticsBillingEngine::normalizeZone).anyMatch(wanted::equals);}
    static Set<String> splitZones(String value){var out=new LinkedHashSet<String>();for(var item:value.split("[/／、,，;；|]"))if(!item.isBlank())out.add(item.trim());return out;}
    static String normalizeZone(String value){return value.replaceAll("[（）()\\s]","").replaceFirst("^澳大利亚","").replace("一区","1区").replace("二区","2区").replace("三区","3区").replace("四区","4区");}
    static boolean available(JsonNode row){var reason=row.path("pendingReason").asText();return !reason.contains("暂停")&&!reason.contains("关停")&&!reason.contains("停收");}
    static String acceptanceTierKey(JsonNode row){return routeKey(row)+"|"+priceKey(row)+"|"+available(row);}
    private static String routeKey(JsonNode row){return String.join("|",row.path("countryCode").asText(row.path("areaName").asText()).toUpperCase(Locale.ROOT),n(row,"weightFromKg").stripTrailingZeros().toPlainString(),n(row,"weightToKg").stripTrailingZeros().toPlainString(),String.valueOf(row.path("weightFromInclusive").asBoolean()),String.valueOf(row.path("weightToInclusive").asBoolean(true)),normalizeZone(row.path("zoneName").asText()));}
    private static String priceKey(JsonNode row){
        var values=new ArrayList<String>();values.add(model(row));values.add(minimum(row).stripTrailingZeros().toPlainString());
        for(var field:List.of("pricePerKg","intervalPrice","firstWeightKg","firstWeightPrice","nextWeightKg","nextWeightPrice","registrationFee","surcharge"))values.add(n(row,field).stripTrailingZeros().toPlainString());
        return String.join("|",values);
    }
    static String model(JsonNode r){return r.path("pricingModel").asText(n(r,"intervalPrice").signum()>0?"interval":n(r,"firstWeightPrice").signum()>0?"first-next":"per-kg");}
    static BigDecimal n(JsonNode r,String key){var v=r.path(key);if(v.isMissingNode()||v.isNull())return BigDecimal.ZERO;if(!v.isNumber()||!Double.isFinite(v.asDouble())||v.decimalValue().signum()<0)throw AppException.unprocessable("非有效非负数："+key);return v.decimalValue();}
    static BigDecimal positive(JsonNode r,String key){var v=n(r,key);if(v.signum()<=0)throw AppException.unprocessable("缺少有效参数："+key);return v;}
}
