package com.milano.quotation.imports;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Component
public class PurchaseImportRowMapper {
    private static final Set<String> REQUIRED_NUMBERS=Set.of("weightG","minOrderQty","purchasePriceCny");
    private final ObjectMapper mapper;
    public PurchaseImportRowMapper(ObjectMapper mapper){this.mapper=mapper;}

    MappedRow map(int sourceRow,String[] values){
        var errors=new ArrayList<String>();var warnings=new ArrayList<String>();
        var sku=clean(values,0).toUpperCase(Locale.ROOT).replaceAll("\\s+","");
        if(sku.isBlank())errors.add("SKU不能为空");
        else if(sku.length()>96||!sku.matches("[A-Z0-9._/-]+"))errors.add("SKU格式不合法");
        else if(reserved(sku))errors.add("TESTP/TEST/DEMO/MOCK/AUTO 前缀为测试SKU，不能作为正式数据");
        var out=mapper.createObjectNode();out.put("sourceRow",sourceRow);out.put("sku",sku);out.put("skuOrigin","imported");
        requiredText(out,"category",clean(values,1),"类别",errors);
        out.put("productImage","");out.put("physicalImage","");out.put("image","");
        requiredText(out,"quotationOwner",clean(values,4),"报价人",errors);
        var date=clean(values,5).replace('/','-').replace('.','-');
        try{out.put("quotationDate",LocalDate.parse(date).toString());}catch(Exception e){out.put("quotationDate",date);errors.add("报价日期无法识别");}
        out.put("size",clean(values,6));out.put("color",clean(values,7));
        number(out,"weightG",values,8,"克重(g)",errors,warnings,false);
        number(out,"lengthCm",values,9,"长(cm)",errors,warnings,false);
        number(out,"widthCm",values,10,"宽(cm)",errors,warnings,false);
        number(out,"heightCm",values,11,"高(cm)",errors,warnings,false);
        number(out,"minOrderQty",values,12,"起订量(件)",errors,warnings,true);
        number(out,"purchasePriceCny",values,13,"基准采购单价",errors,warnings,false);
        number(out,"tier2MinQty",values,14,"阶梯价2起订量",errors,warnings,true);
        number(out,"tier2PriceCny",values,15,"阶梯价2",errors,warnings,false);
        number(out,"tier3MinQty",values,16,"阶梯价3起订量",errors,warnings,true);
        number(out,"tier3PriceCny",values,17,"阶梯价3",errors,warnings,false);
        number(out,"singleFreightCny",values,18,"1件总运费",errors,warnings,false);
        number(out,"freight10Cny",values,19,"10件总运费",errors,warnings,false);
        number(out,"freight100Cny",values,20,"100件总运费",errors,warnings,false);
        choice(out,"freeShipping",clean(values,21),Set.of("是","否"),false,"是否包邮",errors,warnings);
        number(out,"taxIncludedPriceCny",values,22,"含票价",errors,warnings,false);
        out.put("invoiceType",clean(values,23));
        choice(out,"stockStatus",clean(values,24),Set.of("有货","无货","待确认"),true,"是否有货",errors,warnings);
        String[] keys={"notes","factoryInfo","sourceLink1","sourceLink2","sourceLink3","similarSource","auditNotes"};for(int i=0;i<keys.length;i++)out.put(keys[i],clean(values,25+i));
        derive(out,errors,warnings);return new MappedRow(sourceRow,sku,out,List.copyOf(errors),List.copyOf(warnings));
    }
    private static void derive(ObjectNode o,List<String>errors,List<String>warnings){
        boolean ready=errors.isEmpty();o.put("catalogState","ready");o.put("quoteReady",ready);o.put("status",ready?"资料完整":"待补充资料");o.put("name",o.path("category").asText());
        var weight=o.get("weightG");if(weight==null||weight.isNull()){o.putNull("weightKg");o.put("weightDescription","");}else{o.put("weightKg",weight.asDouble()/1000);o.put("weightDescription",weight.asText());}
        o.put("colorSku",o.path("color").asText());for(var f:List.of("material","marks","rawTierPrice","l6Price","freightTrial","invoiceInfo","taxPoint","otherNotes","more"))o.put(f,"");o.putArray("shippingMarks");
        o.put("taxIncludedPrice",o.path("taxIncludedPriceCny").isNumber()?o.path("taxIncludedPriceCny").asText():"");o.put("taxDifference",o.path("invoiceType").asText());o.put("packagingInfo",o.path("factoryInfo").asText());
        var links=o.putArray("sourceLinks");for(var k:List.of("sourceLink1","sourceLink2","sourceLink3","similarSource"))links.add(o.path(k).asText());
        var candidates=new ArrayList<double[]>();addTier(candidates,o,"minOrderQty","purchasePriceCny");addTier(candidates,o,"tier2MinQty","tier2PriceCny");addTier(candidates,o,"tier3MinQty","tier3PriceCny");candidates.sort(Comparator.comparingDouble(v->v[0]));var tiers=o.putArray("priceTiers");for(int i=0;i<candidates.size();i++){var t=tiers.addObject();t.put("minQty",(long)candidates.get(i)[0]);if(i+1<candidates.size())t.put("maxQty",(long)candidates.get(i+1)[0]-1);else t.putNull("maxQty");t.put("unitPriceCny",candidates.get(i)[1]);t.put("source",i==0?"基准采购单价":"阶梯价"+(i+1));}
        var issueArray=o.putArray("importWarnings");warnings.forEach(issueArray::add);errors.forEach(issueArray::add);
    }
    private static void addTier(List<double[]> list,ObjectNode o,String q,String p){if(o.path(q).asDouble(0)>0&&o.path(p).isNumber()&&o.path(p).asDouble(-1)>=0)list.add(new double[]{o.path(q).asDouble(),o.path(p).asDouble()});}
    private static void requiredText(ObjectNode o,String key,String value,String label,List<String>errors){o.put(key,value);if(value.isBlank())errors.add(label+"不能为空");}
    private static void choice(ObjectNode o,String key,String value,Set<String>allowed,boolean required,String label,List<String>errors,List<String>warnings){if(value.isBlank()){o.put(key,"");if(required)errors.add(label+"不能为空");return;}if(!allowed.contains(value)){o.put(key,"");(required?errors:warnings).add(label+"不在可选值中");}else o.put(key,value);}
    private static void number(ObjectNode o,String key,String[] values,int index,String label,List<String>errors,List<String>warnings,boolean integer){var raw=clean(values,index);if(raw.isBlank()){o.putNull(key);if(REQUIRED_NUMBERS.contains(key))errors.add(label+"不能为空");return;}try{var value=new BigDecimal(raw.replaceAll("(?i)CNY|RMB","").replace("¥","").replace("￥","").replace(",","").replace("，","").replaceAll("\\s+","")).stripTrailingZeros();if(value.signum()<0||(integer&&value.scale()>0))throw new NumberFormatException();o.put(key,value);}catch(Exception e){o.putNull(key);(REQUIRED_NUMBERS.contains(key)?errors:warnings).add(label+"不是有效"+(integer?"整数":"非负数字"));}}
    private static String clean(String[] values,int index){return values==null||index>=values.length||values[index]==null?"":values[index].trim();}
    static boolean reserved(String sku){return sku.matches("(?i)^(TESTP|TEST|DEMO|MOCK)[A-Z0-9._/-]*$")||sku.startsWith("AUTO-");}
    record MappedRow(int sourceRow,String sku,ObjectNode payload,List<String>errors,List<String>warnings){}
}
