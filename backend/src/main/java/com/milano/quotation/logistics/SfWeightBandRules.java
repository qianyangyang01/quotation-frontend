package com.milano.quotation.logistics;

import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** SF kilogram tariffs: first weight is a minimum, subsequent weight is a rounding step.
 * Only exact source bands and explicit weight-only clauses are accepted. */
final class SfWeightBandRules {
    private static final String N="[0-9]+(?:\\.[0-9]+)?";
    private static final Pattern BAND=Pattern.compile("(?i)("+N+")\\s*[-－–~～]\\s*("+N+")\\s*(KG|公斤|千克)\\s*[:：]");
    private static final Pattern CLAUSE=Pattern.compile("(?i)(首重|续重)\\s*(?:为|按)?\\s*("+N+")\\s*(KG|G|公斤|千克|克)");
    private record Rule(BigDecimal minimum,BigDecimal step) {}
    static boolean hasScopedBands(String footer){return BAND.matcher(footer).find();}

    /** Explicit draft revalidation only; never applied when reading published or saved quotes. */
    static tools.jackson.databind.node.ArrayNode repairDraft(ObjectNode payload) {
        var audit=tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        if(!payload.path("status").asText().equals("draft")||!payload.path("providerName").asText().equals("顺丰")
                ||!payload.path("channelName").asText().equals("国际电商专递-CD"))return audit;
        for(var value:payload.path("rows")) {
            var row=(ObjectNode)value;
            if(!row.path("countryCode").asText().equals("TH")||!row.path("sourceSheet").asText().equals("国际电商专递-CD")
                    ||!row.path("blockingReason").asText(row.path("pendingReason").asText()).contains("最低计费重量说明冲突，需核对")
                    ||row.path("minChargeWeightKg").asDouble()!=0||row.path("billingStepKg").asDouble()!=0)continue;
            String range=row.path("sourceWeightRange").asText();
            var raw=row.path("rawValues").path("K"+row.path("sourceRow").asInt()).asText();
            if(!range.equals(raw)||range.isBlank())continue;
            var section=Pattern.compile("(?s)泰国[：:]\\s*单票单件计费\\s*\\n(.*?)(?:实重|体积重|$)").matcher(row.path("notes").asText());
            if(!section.find())continue;
            try {
                var original=LogisticsSourceParser.parseRange(range.replaceAll("[（(].*$",""));
                if(original.from()!=row.path("weightFromKg").asDouble()||original.to()!=row.path("weightToKg").asDouble()
                        ||original.includeFrom()!=row.path("weightFromInclusive").asBoolean()||original.includeTo()!=row.path("weightToInclusive").asBoolean())continue;
            }catch(IllegalArgumentException invalid){continue;}
            var repaired=row.deepCopy();
            repaired.put("sourceWeightCell","K"+row.path("sourceRow").asInt());
            apply(repaired,section.group(1),"");
            if(!repaired.path("sourceMinimumWeightKind").asText().equals("weight-band"))continue;
            for(String field:List.of("pendingReason","blockingReason"))repaired.put(field,String.join("；",Arrays.stream(repaired.path(field).asText().split("；"))
                    .filter(s->!s.equals("最低计费重量说明冲突，需核对")).toList()));
            var entry=audit.addObject().put("sourceSheet",row.path("sourceSheet").asText()).put("sourceRow",row.path("sourceRow").asInt())
                    .put("reason","按原表泰国对应重量段识别最低计费重量和0.1kg进位；保留已保存价格及其他问题");
            entry.set("before",row.deepCopy());entry.set("after",repaired.deepCopy());row.removeAll();row.setAll(repaired);
        }
        return audit;
    }

    static boolean apply(ObjectNode row,String footer,String footerCells) {
        String range=row.path("sourceWeightRange").asText();
        var inline=Pattern.compile("[（(]([^）)]*)[）)]").matcher(range);
        String condition=inline.find()?inline.group(1):"";
        boolean inlineRule=condition.contains("首重")||condition.contains("续重");
        var matcher=BAND.matcher(footer);var starts=new ArrayList<Integer>();var ends=new ArrayList<Integer>();
        var matching=new ArrayList<Boolean>();
        while(matcher.find()) {
            starts.add(matcher.start());ends.add(matcher.end());
            matching.add(new BigDecimal(matcher.group(1)).compareTo(row.path("weightFromKg").decimalValue())==0
                    &&new BigDecimal(matcher.group(2)).compareTo(row.path("weightToKg").decimalValue())==0);
        }
        if(!inlineRule&&starts.isEmpty())return false;
        if(!row.path("pricingModel").asText("per-kg").equals("per-kg"))return block(row,"重量段首续重条件与计费方式不一致，需核对");
        var rules=new ArrayList<Rule>();var evidence=new ArrayList<String>();
        if(inlineRule) {var rule=parse(condition);if(rule==null)return block(row,"重量段首续重说明尚未完整识别，需核对");rules.add(rule);evidence.add(range);}
        boolean matched=false;
        for(int i=0;i<starts.size();i++)if(matching.get(i)) {
            matched=true;
            String text=footer.substring(ends.get(i),i+1<starts.size()?starts.get(i+1):footer.length()).split("[；;。\\n]",2)[0].trim();
            var rule=parse(text);
            if(rule==null)return block(row,"重量段首续重说明尚未完整识别，需核对");
            rules.add(rule);evidence.add(footer.substring(starts.get(i),ends.get(i))+text);
        }
        if(!starts.isEmpty()&&!matched)return block(row,"表尾首续重说明未匹配当前重量段，需核对");
        var minima=new TreeSet<BigDecimal>();var steps=new TreeSet<BigDecimal>();
        for(var rule:rules){if(rule.minimum()!=null)minima.add(rule.minimum());if(rule.step()!=null)steps.add(rule.step());}
        if(row.path("minChargeWeightKg").asDouble()>0)minima.add(row.path("minChargeWeightKg").decimalValue());
        if(row.path("billingStepKg").asDouble()>0)steps.add(row.path("billingStepKg").decimalValue());
        var bands=row.path("billingStepBands");
        if(row.hasNonNull("billingStepBands")) {
            if(!LogisticsStepPricing.supported(row)||bands.size()!=1)return block(row,"重量段进位规则与原有分段进位不一致，需核对");
            steps.add(bands.get(0).path("stepKg").decimalValue());
        }
        if(minima.size()>1||steps.size()>1)return block(row,"同一重量段的首续重说明冲突，需核对");
        if(minima.isEmpty()||steps.isEmpty())return block(row,"重量段首续重参数不完整，需核对");
        var minimum=minima.first();var step=steps.first();
        if(minimum.signum()<=0||step.signum()<=0||minimum.compareTo(row.path("weightToKg").decimalValue())>0
                ||minimum.remainder(step).signum()!=0)return block(row,"重量段首续重参数无效，需核对");
        if(!row.hasNonNull("billingStepBands"))row.put("billingStepKg",step);
        row.put("minChargeWeightKg",minimum)
                .put("sourceMinimumWeightKind","weight-band").put("sourceMinimumWeightText",String.join("；",evidence))
                .put("sourceMinimumWeightCell",String.join(",",java.util.stream.Stream.of(row.path("sourceWeightCell").asText(),matched?footerCells:"").filter(s->!s.isBlank()).toList()))
                .put("sourceBillingStepText",String.join("；",evidence));
        for(String field:List.of("pendingReason","blockingReason","reviewWarning"))if(row.has(field))
            row.put(field,String.join("；",Arrays.stream(row.path(field).asText().split("[；;]")).map(String::trim)
                    .filter(s->!s.isBlank()&&!s.equals("重量范围附带首续重条件，需核对完整规则")).toList()));
        return true;
    }

    private static Rule parse(String text) {
        var matcher=CLAUSE.matcher(text);BigDecimal minimum=null,step=null;
        while(matcher.find()) {
            var value=LogisticsMinimumWeight.kilograms(matcher.group(2),matcher.group(3));
            if(matcher.group(1).equals("首重")){if(minimum!=null&&minimum.compareTo(value)!=0)return null;minimum=value;}
            else {if(step!=null&&step.compareTo(value)!=0)return null;step=value;}
        }
        // Monetary first/next tariffs and unrecognized qualifications must remain blocked.
        if(!CLAUSE.matcher(text).replaceAll("").replaceAll("[\\s,，、]","").isEmpty())return null;
        return minimum==null&&step==null?null:new Rule(minimum,step);
    }
    private static boolean block(ObjectNode row,String reason){LogisticsReadiness.block(row,reason);return true;}
}
