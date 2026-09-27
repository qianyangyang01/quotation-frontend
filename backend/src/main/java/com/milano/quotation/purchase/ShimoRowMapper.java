package com.milano.quotation.purchase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Converts the verified new-2026 text columns to the same sparse patches as clipboard paste. */
final class ShimoRowMapper {
    static final String FILE_GUID = "m4kMMD8Vm2hdrPkD";
    static final String[] HEADERS = {"报价日期","报价人","备注","SKU","克重(g)","尺码","颜色","材质","长(cm)","宽(cm)","高(cm)","起订量(件)","基准采购单价(CNY/件)","阶梯价2起订量","阶梯价2(CNY/件)","阶梯价3起订量","阶梯价3(CNY/件)","1件总运费(CNY)","10件总运费(CNY)","100件总运费(CNY)","是否包邮","含票价(CNY/件)","票点","票类型","类别","是否有货","工厂信息","审核备注","货源链接1","货源链接2","货源链接3","相似货源"};
    static final String[] FIELDS = {"quotationDate","quotationOwner","notes","sku","weightG","size","color","material","lengthCm","widthCm","heightCm","minOrderQty","purchasePriceCny","tier2MinQty","tier2PriceCny","tier3MinQty","tier3PriceCny","singleFreightCny","freight10Cny","freight100Cny","freeShipping","taxIncludedPriceCny","taxPoint","invoiceType","category","stockStatus","factoryInfo","auditNotes","sourceLink1","sourceLink2","sourceLink3","similarSource"};
    private static final Set<Integer> NUMBERS = Set.of(4,8,9,10,11,12,13,14,15,16,17,18,19,21,22);
    private static final tools.jackson.databind.ObjectMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    private ShimoRowMapper() {}
    static void validateHeader(JsonNode row) {
        for (int i=0;i<HEADERS.length;i++)
            if (!HEADERS[i].equals(text(row,i).replace("*", "").replaceAll("\\s+", "")))
                throw new IllegalArgumentException("新版表表头不匹配：第"+(i+4)+"列应为"+HEADERS[i]);
    }
    static String sku(JsonNode row) { return text(row,3).replaceAll("\\s+", "").toUpperCase(Locale.ROOT); }
    static String text(JsonNode row,int i) { var n=row.path(i); return n.isMissingNode()||n.isNull()?"":n.asText().trim(); }
    static ObjectNode patch(JsonNode row) {
        var p=JsonNodeFactory.instance.objectNode().put("sourceRow",1);
        for(int i=0;i<FIELDS.length;i++) {
            var value=text(row,i); if(value.isEmpty()) continue;
            if(NUMBERS.contains(i)) {
                var raw=i==22?value.replaceAll("[%％]$", ""):value;
                if(!raw.matches("(?:\\d+(?:\\.\\d*)?|\\.\\d+)")) throw new IllegalArgumentException(HEADERS[i]+"须为有效非负数字");
                var number=new BigDecimal(raw);
                if(i==22&&(value.matches(".*[%％]$")||number.compareTo(BigDecimal.ONE)>0)) number=number.movePointLeft(2);
                // Match JSON request/JSONB numeric node types. DecimalNode(130) versus IntNode(130)
                // otherwise looks dirty to Hibernate on every flush and corrupts version checkpoints.
                p.set(FIELDS[i],JSON.readTree(number.stripTrailingZeros().toPlainString()));
            } else p.put(FIELDS[i],value);
        }
        p.put("sku",sku(row));
        // Check source cells, not the merged system record: missing mandatory cells must block updates too.
        if(!p.hasNonNull("weightG")||p.path("weightG").decimalValue().signum()<=0) throw new IllegalArgumentException("待补齐：克重须大于0");
        if(!p.hasNonNull("taxPoint")) throw new IllegalArgumentException("待补齐：请填写票点，无票点请明确填0%");
        if(p.path("taxPoint").decimalValue().compareTo(BigDecimal.ONE)>0) throw new IllegalArgumentException("票点须在0%至100%之间");
        if(p.has("stockStatus")) {
            if(p.path("stockStatus").asText().equals("有")) p.put("stockStatus","有货");
            if(p.path("stockStatus").asText().equals("无")) p.put("stockStatus","无货");
        }
        if(p.has("quotationDate")) {
            String date=p.path("quotationDate").asText().replace('.','-').replace('/','-');
            try {
                if(!date.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) throw new IllegalArgumentException();
                var parts=date.split("-");
                p.put("quotationDate",LocalDate.of(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2])).toString());
            } catch(RuntimeException e) { throw new IllegalArgumentException("报价日期格式应为2026-09-05或2026.9.5"); }
        }
        return p;
    }
}
