package com.milano.quotation.imports;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class PurchaseImportRowMapper {
    private static final Pattern FIRST_NUMBER=Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private final ObjectMapper mapper;
    public PurchaseImportRowMapper(ObjectMapper mapper){this.mapper=mapper;}

    MappedRow map(int sourceRow,String[] values){return map("采购产品导入",sourceRow,values,new PurchaseWorkbookSchema(PurchaseWorkbookSchema.Version.LEGACY));}

    MappedRow map(String sourceSheet,int sourceRow,String[] values,PurchaseWorkbookSchema schema){
        var errors=new ArrayList<String>();var warnings=new ArrayList<String>();
        var rawSku=clean(values,schema.sku()).toUpperCase(Locale.ROOT);var sku=rawSku.replaceAll("\\s+","");
        if(sku.isBlank())errors.add("SKU不能为空");
        else if(rawSku.matches(".*\\s+.*"))errors.add("SKU中不能包含空格");
        else if(sku.length()>96||!sku.matches("[A-Z0-9._/-]+"))errors.add("SKU格式不合法");
        else if(reserved(sku))errors.add("TESTP/TEST/DEMO/MOCK/AUTO 前缀为测试SKU，不能作为正式数据");
        var out=mapper.createObjectNode();out.put("sourceSheet",sourceSheet);out.put("sourceRow",sourceRow);out.put("sku",sku);out.put("skuOrigin","imported");
        optionalText(out,"category",clean(values,schema.category()),"类别",warnings);
        out.put("productImage","");out.put("physicalImage","");out.put("image","");
        optionalText(out,"quotationOwner",clean(values,schema.owner()),"报价人",warnings);
        var date=clean(values,schema.date()).replace('/','-').replace('.','-');
        if(date.isBlank()){out.put("quotationDate","");warnings.add("报价日期暂无数据");}
        else try{out.put("quotationDate",LocalDate.parse(date,DateTimeFormatter.ofPattern("yyyy-M-d")).toString());}catch(Exception e){out.put("quotationDate",date);warnings.add("报价日期无法识别，已按原文保存");}
        out.put("size",clean(values,schema.size()));out.put("color",clean(values,schema.color()));out.put("material",clean(values,schema.material()));
        fuzzyNumber(out,"weightG",values,schema.weight(),"克重(g)",warnings,false,true);
        number(out,"lengthCm",values,schema.length(),"长(cm)",warnings,false);
        number(out,"widthCm",values,schema.widthCm(),"宽(cm)",warnings,false);
        number(out,"heightCm",values,schema.height(),"高(cm)",warnings,false);
        number(out,"minOrderQty",values,schema.moq(),"起订量(件)",warnings,true);
        number(out,"purchasePriceCny",values,schema.basePrice(),"基准采购单价",warnings,false);
        number(out,"tier2MinQty",values,schema.tier2Qty(),"阶梯价2起订量",warnings,true);
        number(out,"tier2PriceCny",values,schema.tier2Price(),"阶梯价2",warnings,false);
        number(out,"tier3MinQty",values,schema.tier3Qty(),"阶梯价3起订量",warnings,true);
        number(out,"tier3PriceCny",values,schema.tier3Price(),"阶梯价3",warnings,false);
        number(out,"singleFreightCny",values,schema.freight1(),"1件总运费",warnings,false);
        number(out,"freight10Cny",values,schema.freight10(),"10件总运费",warnings,false);
        number(out,"freight100Cny",values,schema.freight100(),"100件总运费",warnings,false);
        choice(out,"freeShipping",clean(values,schema.freeShipping()),Set.of("是","否"),"是否包邮",warnings);
        number(out,"taxIncludedPriceCny",values,schema.taxIncludedPrice(),"含票价",warnings,false);
        taxPoint(out,clean(values,schema.taxPoint()),warnings,schema.international());
        out.put("invoiceType",normalizeInvoiceType(clean(values,schema.invoiceType()),warnings,schema.international()));
        stock(out,clean(values,schema.stock()),warnings);
        out.put("notes",clean(values,schema.notes()));out.put("factoryInfo",clean(values,schema.factory()));out.put("auditNotes",clean(values,schema.auditNotes()));
        out.put("sourceLink1",clean(values,schema.link1()));out.put("sourceLink2",clean(values,schema.link2()));out.put("sourceLink3",clean(values,schema.link3()));out.put("similarSource",clean(values,schema.similar()));
        derive(out,errors,warnings);return new MappedRow(sourceSheet,sourceRow,sku,out,List.copyOf(errors),List.copyOf(warnings));
    }

    private static void derive(ObjectNode o,List<String> errors,List<String> warnings){
        boolean usablePrice=nonNegative(o.get("purchasePriceCny"))||nonNegative(o.get("tier2PriceCny"))||nonNegative(o.get("tier3PriceCny"))||nonNegative(o.get("taxIncludedPriceCny"));
        boolean ready=errors.isEmpty()&&positive(o.get("weightG"))&&positive(o.get("minOrderQty"))&&usablePrice;
        o.put("catalogState",ready?"ready":"pending_template");o.put("quoteReady",ready);o.put("status",ready?"资料完整":"模板待补全（不可报价）");
        o.put("name",o.path("category").asText().isBlank()?"商品 "+o.path("sku").asText():o.path("category").asText());
        var weight=o.get("weightG");if(weight==null||weight.isNull()){o.putNull("weightKg");o.put("weightDescription","");}else{o.put("weightKg",weight.asDouble()/1000);o.put("weightDescription",weight.asText());}
        o.put("colorSku",o.path("color").asText());for(var f:List.of("marks","rawTierPrice","l6Price","freightTrial","invoiceInfo","otherNotes","more"))o.put(f,"");o.putArray("shippingMarks");
        o.put("taxIncludedPrice",o.path("taxIncludedPriceCny").isNumber()?o.path("taxIncludedPriceCny").asText():"");o.put("taxDifference",o.path("invoiceType").asText());o.put("packagingInfo",o.path("factoryInfo").asText());
        var links=o.putArray("sourceLinks");for(var k:List.of("sourceLink1","sourceLink2","sourceLink3","similarSource"))links.add(o.path(k).asText());
        var candidates=new ArrayList<double[]>();addTier(candidates,o,"minOrderQty","purchasePriceCny");addTier(candidates,o,"tier2MinQty","tier2PriceCny");addTier(candidates,o,"tier3MinQty","tier3PriceCny");
        candidates.sort(Comparator.comparingDouble(v->v[0]));var tiers=o.putArray("priceTiers");for(int i=0;i<candidates.size();i++){var t=tiers.addObject();t.put("minQty",(long)candidates.get(i)[0]);if(i+1<candidates.size())t.put("maxQty",(long)candidates.get(i+1)[0]-1);else t.putNull("maxQty");t.put("unitPriceCny",candidates.get(i)[1]);t.put("source",i==0?"基准采购单价":"阶梯价"+(i+1));}
        var issueArray=o.putArray("importWarnings");warnings.forEach(issueArray::add);errors.forEach(issueArray::add);
    }
    private static void optionalText(ObjectNode o,String key,String value,String label,List<String>warnings){o.put(key,value);if(value.isBlank())warnings.add(label+"暂无数据");}
    private static void choice(ObjectNode o,String key,String value,Set<String>allowed,String label,List<String>warnings){if(value.isBlank()){o.put(key,"");return;}if(!allowed.contains(value)){o.put(key,"");warnings.add(label+"“"+value+"”无法识别，已显示暂无数据");}else o.put(key,value);}
    private static void stock(ObjectNode o,String value,List<String>warnings){if(value.equals("有"))value="有货";if(value.isBlank()||Set.of("有货","无货","待确认","定制款").contains(value)){o.put("stockStatus",value);return;}o.put("stockStatus",value);warnings.add("库存状态“"+value+"”已按原文保存");}
    private static String normalizeInvoiceType(String value,List<String>warnings,boolean international){if(!international)return value;if(value.matches("普票(?:\\d+(?:\\.\\d+)?%?)?")){if(!value.equals("普票"))warnings.add("票类型“"+value+"”已规范为“普票”");return "普票";}if(value.matches("专票(?:\\d+(?:\\.\\d+)?%?)?")){if(!value.equals("专票"))warnings.add("票类型“"+value+"”已规范为“专票”");return "专票";}return value;}
    private static void taxPoint(ObjectNode o,String raw,List<String>warnings,boolean explicit){if(!explicit){o.remove("taxPoint");return;}if(raw.isBlank()){o.putNull("taxPoint");return;}try{var text=raw.replace("％","%").trim();boolean percent=text.endsWith("%");if(percent)text=text.substring(0,text.length()-1);var value=new BigDecimal(text.replace(",","").trim());if(value.signum()<0)throw new NumberFormatException();if(percent||value.compareTo(BigDecimal.ONE)>0)value=value.movePointLeft(2);if(value.compareTo(BigDecimal.ONE)>0)throw new NumberFormatException();o.put("taxPoint",value.stripTrailingZeros());}catch(Exception e){o.putNull("taxPoint");warnings.add("票点“"+raw+"”无法识别，已显示暂无数据");}}
    private static void fuzzyNumber(ObjectNode o,String key,String[]values,int index,String label,List<String>warnings,boolean integer,boolean preserve){var raw=clean(values,index);if(preserve)o.put("weightOriginal",raw);if(raw.isBlank()){o.putNull(key);return;}var cleaned=cleanNumber(raw);try{var value=new BigDecimal(cleaned).stripTrailingZeros();if(value.signum()<0||(integer&&value.scale()>0))throw new NumberFormatException();o.put(key,value);return;}catch(Exception ignored){}var match=FIRST_NUMBER.matcher(cleaned);if(match.find())try{var value=new BigDecimal(match.group()).stripTrailingZeros();if(value.signum()<0||(integer&&value.scale()>0))throw new NumberFormatException();o.put(key,value);warnings.add(label+"“"+raw+"”已提取为"+value.toPlainString());return;}catch(Exception ignored){}o.putNull(key);warnings.add(label+"“"+raw+"”不是有效数字，已显示暂无数据");}
    private static void number(ObjectNode o,String key,String[]values,int index,String label,List<String>warnings,boolean integer){var raw=clean(values,index);if(raw.isBlank()){o.putNull(key);return;}try{var value=new BigDecimal(cleanNumber(raw)).stripTrailingZeros();if(value.signum()<0||(integer&&value.scale()>0))throw new NumberFormatException();o.put(key,value);}catch(Exception e){o.putNull(key);warnings.add(label+"“"+raw+"”不是有效"+(integer?"整数":"非负数字")+"，已显示暂无数据");}}
    private static String cleanNumber(String raw){return raw.replaceAll("(?i)CNY|RMB","").replace("¥","").replace("￥","").replace(",","").replace("，","").replaceAll("\\s+","");}
    private static void addTier(List<double[]> list,ObjectNode o,String q,String p){if(positive(o.get(q))&&nonNegative(o.get(p)))list.add(new double[]{o.get(q).asDouble(),o.get(p).asDouble()});}
    private static boolean positive(tools.jackson.databind.JsonNode node){return node!=null&&!node.isNull()&&node.asDouble()>0;}
    private static boolean nonNegative(tools.jackson.databind.JsonNode node){return node!=null&&!node.isNull()&&node.isNumber()&&node.asDouble()>=0;}
    private static String clean(String[] values,int index){return index<0||values==null||index>=values.length||values[index]==null?"":values[index].trim();}
    static boolean reserved(String sku){return sku.matches("(?i)^(TESTP|TEST|DEMO|MOCK)[A-Z0-9._/-]*$")||sku.startsWith("AUTO-");}
    record MappedRow(String sourceSheet,int sourceRow,String sku,ObjectNode payload,List<String>errors,List<String>warnings){MappedRow(int sourceRow,String sku,ObjectNode payload,List<String>errors,List<String>warnings){this("采购产品导入",sourceRow,sku,payload,errors,warnings);}}
}
