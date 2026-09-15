package com.milano.quotation.logistics;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Minimum freight weight only. Return/re-delivery fees and weight rounding are separate rules. */
final class LogisticsMinimumWeight {
    private static final String NUMBER="([0-9]+(?:\\.[0-9]+)?)";
    private static final String UNIT="(KG|G|公斤|千克|克)";
    private static final Pattern WEIGHT=Pattern.compile("(?i)"+NUMBER+"\\s*"+UNIT);
    private static final Pattern MINIMUM=Pattern.compile("(?i)(?:(?:最[小低]计费重(?:量)?|计费起重|起重|首重)(?:为|[:：])?\\s*"+NUMBER+"\\s*"+UNIT+"|"+NUMBER+"\\s*"+UNIT+"\\s*(?:起重|起计费|起计|起收)|(?:不足|不满|低于)\\s*"+NUMBER+"\\s*"+UNIT+"(?:的部分|均)?\\s*(?:按|按照)\\s*"+NUMBER+"\\s*"+UNIT+"\\s*(?:计费|计价|计算))");
    private static final Map<String,String> COUNTRIES=LogisticsSourceParser.minimumWeightCountries();
    private static final Pattern COUNTRY=Pattern.compile(COUNTRIES.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote).reduce((a,b)->a+"|"+b).orElseThrow());
    record Rule(BigDecimal kg,Set<String> countries,boolean other,String text) {}
    record Resolution(BigDecimal kg,String evidence,boolean conflict) {}

    static Resolution fromNotes(String notes,String country) {
        var rules=new ArrayList<Rule>();
        for(var paragraph:notes.replaceAll("\\r", "").split("[\\n；;。]")) {
            if(paragraph.matches("(?s).*(重派|退件|退回|销毁|退运|仓储|续重|进位).*")) {
                // A minimum followed by rounding is valid; a rounding increment alone is not a minimum.
                if(paragraph.matches("(?s).*(重派|退件|退回|销毁|退运|仓储|续重).*")||!paragraph.matches("(?s).*(起重|最[小低]计费|首重|不足.{0,15}按).*") )continue;
            }
            var matcher=MINIMUM.matcher(paragraph);int previous=0;Set<String> context=Set.of();boolean other=false;
            while(matcher.find()) {
                var prefix=paragraph.substring(previous,matcher.start());
                var names=new LinkedHashSet<String>();var cm=COUNTRY.matcher(prefix);while(cm.find())names.add(COUNTRIES.get(cm.group()));
                boolean isOther=prefix.matches("(?s).*(其他|其余)国家.*");
                if(!names.isEmpty()||isOther){context=names;other=isOther;}
                var matched=matcher.group();var weights=WEIGHT.matcher(matched);BigDecimal kg=null;boolean unequal=false;
                while(weights.find()){var value=kilograms(weights.group(1),weights.group(2));if(kg!=null&&kg.compareTo(value)!=0)unequal=true;kg=value;}
                // “无50g起重” explicitly removes that floor for the named countries.
                if(prefix.stripTrailing().endsWith("无"))kg=BigDecimal.ZERO;
                if(kg!=null&&!unequal)rules.add(new Rule(kg,Set.copyOf(context),other,prefix+matched));
                previous=matcher.end();
            }
        }
        var named=new HashSet<String>();rules.forEach(r->named.addAll(r.countries));
        int priority=0;var values=new TreeSet<BigDecimal>();var evidence=new LinkedHashSet<String>();
        for(var rule:rules){int p=rule.countries.contains(country)?3:rule.other&&!named.contains(country)?2:rule.countries.isEmpty()&&!rule.other?1:0;
            if(p==0||p<priority)continue;if(p>priority){priority=p;values.clear();evidence.clear();}values.add(rule.kg);evidence.add(rule.text.trim());}
        return new Resolution(values.isEmpty()?null:values.first(),String.join("；",evidence),values.size()>1);
    }

    static BigDecimal kilograms(String number,String unit) {
        var value=new BigDecimal(number);return unit.matches("(?i)KG|公斤|千克")?value:value.movePointLeft(3);
    }

    static void applyNotes(ObjectNode row,String notes,String cell) {
        if(Set.of("column","column-inherited").contains(row.path("sourceMinimumWeightKind").asText())||row.path("minChargeWeightKg").asDouble()>0)return;
        var result=fromNotes(notes,row.path("countryCode").asText());
        if(result.conflict){LogisticsReadiness.block(row,"最低计费重量说明冲突，需核对");return;}
        if(result.kg!=null){row.put("minChargeWeightKg",result.kg).put("sourceMinimumWeightKind","note").put("sourceMinimumWeightText",result.evidence);
            if(!cell.isBlank())row.put("sourceMinimumWeightCell",cell);}
    }

    static void applyStoredNotes(ObjectNode channel) {
        for(var value:channel.path("rows")) {
            var row=(ObjectNode)value;
            applyNotes(row,row.path("notes").asText(),"");
            if(row.path("minChargeWeightKg").asDouble()==0&&!row.has("sourceMinimumWeightKind"))applyNotes(row,channel.path("sourceNotes").asText(),"");
        }
    }
}
