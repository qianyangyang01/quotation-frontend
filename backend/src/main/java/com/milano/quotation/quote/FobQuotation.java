package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import com.milano.quotation.fob.FobPurchaseService;
import com.milano.quotation.purchase.PurchaseProductService;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.math.*;
import java.util.*;

/** FOB snapshots share record ownership/review, but never channel pricing or logistics. */
@Service
public class FobQuotation {
    private final FobPurchaseService fob;
    private final PurchaseProductService purchases;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    public FobQuotation(FobPurchaseService fob, PurchaseProductService purchases, JdbcClient jdbc, ObjectMapper mapper) {
        this.fob=fob; this.purchases=purchases; this.jdbc=jdbc; this.mapper=mapper;
    }
    static boolean isFob(JsonNode input) { return "fob".equals(input.path("quoteMode").asText()); }
    static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
    static BigDecimal amount(JsonNode n) {
        if (!n.isNumber() || !Double.isFinite(n.asDouble()) || n.decimalValue().signum()<0 || n.decimalValue().compareTo(new BigDecimal("1000000000"))>0)
            throw AppException.unprocessable("FOB金额无效");
        return n.decimalValue();
    }
    static long count(JsonNode n) {
        if (!n.isIntegralNumber() || !n.canConvertToLong() || n.asLong()<1 || n.asLong()>9007199254740991L)
            throw AppException.unprocessable("FOB数量须为有效正整数");
        return n.asLong();
    }
    static String text(JsonNode n, int max, boolean required) {
        if (!n.isTextual() || n.asText().length()>max || required && n.asText().isBlank()) throw AppException.unprocessable("FOB文本内容无效或过长");
        return n.asText();
    }
    public ObjectNode prepare(ObjectNode request) {
        if(request.has("priorityProcessing")&&!request.path("priorityProcessing").isBoolean())throw AppException.unprocessable("优先处理参数无效");
        var snapshot=request.path("fob");
        if(snapshot.path("schemaVersion").asInt()==2)return prepareBatch(request);
        if(snapshot.path("schemaVersion").asInt()!=1)throw AppException.unprocessable("FOB报价快照版本无效");
        var product=snapshot.path("product");
        var sku=text(product.path("sku"),96,true);
        if (!sku.matches("[A-Z0-9][A-Z0-9._/-]{0,95}")) throw AppException.unprocessable("FOB SKU无效");
        var source=product.path("source").asText();
        if (!Set.of("fob","standard").contains(source)) throw AppException.unprocessable("FOB采购来源无效");
        // Lock the finance row for this save; rate changes cannot pass a stale preview.
        var rate=currentRate();
        if (rate.signum()==0 || rate.compareTo(amount(snapshot.path("rate")))!=0) throw AppException.conflict("汇率已变化，请更新报价后再保存");
        var current=source.equals("fob")?fob.getForQuotation(sku):purchases.getForQuotation(sku);
        var timestamp=current.path(source.equals("fob")?"updatedAt":"_updatedAt").asText();
        if (timestamp.isBlank() || !timestamp.equals(product.path("updatedAt").asText())) throw AppException.conflict("采购资料已更新，请重新查询SKU后再保存");
        var parsed=source.equals("fob")?current.path("parsed").deepCopy():standard(current);
        var canonical=object().put("sku",sku).put("source",source).put("category",current.path("category").asText())
            .put("weight",source.equals("fob")?current.path("weightRaw").asText("未填写"):current.hasNonNull("weightG")?current.path("weightG").asText()+" g":"未填写").put("updatedAt",timestamp);
        canonical.set("parsed",parsed); canonical.putArray("notices");
        var result=calculate(request,canonical,rate);
        // Full source is retained for audit; future source changes never reprice this snapshot.
        ((ObjectNode)result.path("fob")).set("sourceSnapshot",current.deepCopy());
        return result;
    }
    // All products are validated in the enclosing quotation-create transaction. No partial records.
    ObjectNode prepareBatch(ObjectNode request) {
        var input=request.path("fob");var entries=input.path("items");
        if(!entries.isArray()||entries.isEmpty()||entries.size()>100)throw AppException.unprocessable("批量FOB报价须包含1–100行商品");
        var results=new ArrayList<ObjectNode>();var unique=new HashSet<String>();
        for(var entry:entries) {
            if(entry.path("schemaVersion").asInt()!=1)throw AppException.unprocessable("批量报价不能嵌套批量快照");
            if(!entry.path("rate").equals(input.path("rate"))||!entry.path("policy").equals(input.path("policy"))||!entry.path("displayMode").equals(input.path("displayMode")))throw AppException.unprocessable("批量报价必须使用相同汇率、规则和展示方式");
            var identity=entry.at("/product/sku").asText()+":"+("tiers".equals(entry.path("displayMode").asText())?"tiers":entry.path("quantity").asText());
            if(!unique.add(identity))throw AppException.unprocessable("批量报价中存在重复SKU和数量范围");
            var child=object().put("quoteMode","fob");child.set("customerName",request.path("customerName"));child.set("fob",entry.deepCopy());
            try {
                var result=prepare(child);var raw=result.at("/fob/sourceSnapshot");
                if("standard".equals(entry.at("/product/source").asText()) && "是".equals(raw.path("freeShipping").asText())) {
                    for(var field:List.of("singleFreightCny","freight10Cny","freight100Cny"))if(raw.path(field).isNumber()&&raw.path(field).asDouble()>0)throw AppException.unprocessable("采购资料冲突：包邮与正数运费同时存在");
                }
                results.add(result);
            } catch(AppException e) { throw new AppException(e.status(),e.code(),entry.at("/product/sku").asText()+"："+e.getMessage()); }
        }
        return combineBatch(request,results);
    }
    static ObjectNode combineBatch(ObjectNode request,List<ObjectNode> results) {
        var input=request.path("fob");var sheetInput=input.path("sheet");var first=results.get(0).path("fob");
        if(!sheetInput.isObject())throw AppException.unprocessable("缺少合并报价单");
        // Reuse the single-sheet metadata contract. Row prices are verified against every canonical item below.
        var metadata=(ObjectNode)sheetInput.deepCopy();metadata.set("rows",first.at("/sheet/rows"));
        var sheet=validateSheet(metadata,(ArrayNode)first.path("ranges"),first.at("/product/sku").asText());
        var expected=new LinkedHashMap<String,ObjectNode>();var items=JsonNodeFactory.instance.arrayNode();
        for(int i=0;i<results.size();i++) {
            var item=(ObjectNode)results.get(i).path("fob");items.add(item);
            if(!item.at("/sheet/quantityLabels").equals(sheet.path("quantityLabels")))throw AppException.unprocessable("合并报价单价格列顺序不一致");
            for(var row:item.at("/sheet/rows")) {var copy=(ObjectNode)row.deepCopy();var key=i+":"+row.path("key").asText();copy.put("key",key);expected.put(key,copy);}
        }
        var submitted=sheetInput.path("rows");
        if(!submitted.isArray()||submitted.size()!=expected.size()||submitted.size()>2000)throw AppException.conflict("合并报价单商品或阶梯已变化，请重新生成（最多2000行阶梯）");
        var rows=sheet.putArray("rows");
        for(var row:submitted) {
            var canonical=expected.remove(row.path("key").asText());
            if(canonical==null)throw AppException.unprocessable("合并报价单包含重复或未知商品行");
            var prices=row.path("prices");if(!prices.isArray()||prices.size()!=2)throw AppException.unprocessable("合并报价单价格无效");
            for(int j=0;j<2;j++)if(amount(prices.get(j)).compareTo(amount(canonical.path("prices").get(j)))!=0)throw AppException.conflict("合并报价价格与当前资料不一致，请重新查询");
            canonical.put("number",rows.size()+1);rows.add(canonical);
        }
        sheet.put("priceGroupLabel","FOB Final Unit Price (USD)");
        sheet.put("fobBatch",true).put("customerName",request.path("customerName").asText().equals("预览客户")?"":request.path("customerName").asText());
        var snapshot=(ObjectNode)first.deepCopy();snapshot.put("schemaVersion",2);snapshot.set("items",items);snapshot.set("sheet",sheet);
        // A batch has multiple alternative unit prices, so it has no meaningful single system price.
        snapshot.remove(List.of("current","tiers","ranges","sourceSnapshot"));
        var payload=results.get(0).deepCopy();payload.set("fob",snapshot);
        var skus=new LinkedHashSet<String>();for(var item:items)skus.add(item.at("/product/sku").asText());
        payload.put("primarySku",String.join(", ",skus));
        var categories=new HashSet<String>();for(var item:items)categories.add(item.at("/product/category").asText("其他"));
        payload.put("productCategory",categories.size()==1?categories.iterator().next():"多品类");
        payload.put("productSummary","FOB批量 · "+skus.size()+"个SKU · "+String.join(" / ",skus));
        payload.putNull("systemQuoteUsd").putNull("systemQuoteCny").putNull("totalCostCny");
        return payload;
    }
    static void validateBatchDeal(ObjectNode current,ObjectNode patch) {
        if(current.at("/fob/schemaVersion").asInt()!=2 || !patch.has("dealLines"))return;
        var items=current.at("/fob/items");var seen=new HashSet<String>();var total=BigDecimal.ZERO;long quantity=0;
        for(var line:patch.path("dealLines")) {
            var id=line.path("optionId").asText();var match=java.util.regex.Pattern.compile("fob:(\\d{1,3}):(declared|undeclared)").matcher(id);
            if(!match.matches()||Integer.parseInt(match.group(1))>=items.size()||!seen.add(id))throw AppException.unprocessable("FOB成交商品不存在或重复");
            var item=items.get(Integer.parseInt(match.group(1)));long count=count(line.path("quantity"));
            var price=amount(line.path("unitPriceUsd"));var amount=price.multiply(BigDecimal.valueOf(count)).setScale(2,RoundingMode.HALF_UP);
            if(amount.compareTo(amount(line.path("amountUsd")))!=0)throw AppException.unprocessable("FOB成交金额与单价、数量不一致");
            total=total.add(amount);quantity=Math.addExact(quantity,count);
            ((ObjectNode)line).put("optionLabel",item.at("/product/sku").asText()+" · "+(match.group(2).equals("declared")?"报关":"不报关")).put("country","").put("carrier","").put("channel","");
        }
        if("won".equals(patch.path("status").asText(current.path("status").asText()))&&seen.isEmpty())throw AppException.unprocessable("请填写FOB成交商品明细");
        if(!seen.isEmpty()) {
            if(total.compareTo(amount(patch.path("actualQuoteUsd")))!=0||quantity!=count(patch.path("dealQuantity")))throw AppException.unprocessable("FOB成交总额或总数量不一致");
            var cny=total.multiply(amount(current.path("exchangeRate"))).setScale(2,RoundingMode.HALF_UP);
            patch.put("actualQuoteCny",cny);
        }
    }
    BigDecimal currentRate() {
        var values=jdbc.sql("select payload from finance_setting where setting_key='exchange-rate' for share")
            .query((rs,n)->mapper.readTree(rs.getString(1))).list();
        if(values.isEmpty())throw AppException.conflict("缺少财务汇率，请刷新后重试");
        var setting=values.get(0);return amount(setting.has("usdCny")?setting.path("usdCny"):setting.path("usdToCny"));
    }
    static ObjectNode standard(JsonNode raw) {
        if (!"standard".equals(raw.path("dataSource").asText()) || !"ready".equals(raw.path("catalogState").asText())
            || "system".equals(raw.path("skuOrigin").asText()) || "tax_included".equals(raw.path("purchasePriceBasis").asText()))
            throw AppException.unprocessable("该新采购资料不满足FOB报价条件");
        var parsed=object().put("minOrderQty",count(raw.path("minOrderQty"))).put("orderMultiple",1);
        var candidates=new ArrayList<ObjectNode>();
        for (var pair:List.of(new String[]{"minOrderQty","purchasePriceCny"},new String[]{"tier2MinQty","tier2PriceCny"},new String[]{"tier3MinQty","tier3PriceCny"})) {
            if (!raw.hasNonNull(pair[0]) || !raw.hasNonNull(pair[1])) continue;
            candidates.add(object().put("minQty",count(raw.path(pair[0]))).put("unitPriceCny",amount(raw.path(pair[1]))).put("unit","件"));
        }
        candidates.sort(Comparator.comparingLong(n->n.path("minQty").asLong()));
        var tiers=parsed.putArray("priceTiers");
        for(int i=0;i<candidates.size();i++) {var row=candidates.get(i);if(i+1<candidates.size())row.put("maxQty",candidates.get(i+1).path("minQty").asLong()-1);else row.putNull("maxQty");tiers.add(row);}
        var freight="是".equals(raw.path("freeShipping").asText())?BigDecimal.ZERO:amount(raw.path("freight100Cny"));
        parsed.set("freight",object().put("quantity",100).put("totalFreightCny",freight).put("unitFreightCny",freight.movePointLeft(2)).put("estimated",false).put("basis","100件总运费 "+freight.toPlainString()+" ÷ 100"));
        return parsed;
    }
    static String price(BigDecimal cost,int extra,String factor,BigDecimal rate) {return cost.add(BigDecimal.valueOf(extra)).multiply(new BigDecimal(factor)).divide(rate,2,RoundingMode.HALF_UP).toPlainString();}
    static ObjectNode quote(JsonNode row,long min,Long max,BigDecimal rate,boolean single) {
        var cost=amount(row.path("costCny"));int extra=single && cost.multiply(BigDecimal.valueOf(min)).compareTo(BigDecimal.valueOf(200))<0?1:0;
        var out=object().put("minQty",min).put("unit",row.path("unit").asText()).put("declaredUsd",price(cost,extra,"1.14",rate)).put("undeclaredUsd",price(cost,extra,"1.1628",rate));
        if(max==null)out.putNull("maxQty");else out.put("maxQty",max);return out;
    }
    static ObjectNode calculate(ObjectNode request,ObjectNode product,BigDecimal rate) {
        var input=request.path("fob"); var parsed=product.path("parsed");
        long moq=count(parsed.path("minOrderQty")),multiple=count(parsed.path("orderMultiple")),quantity=count(input.path("quantity"));
        if(quantity<moq || quantity%multiple!=0)throw AppException.unprocessable("报价数量不满足起订量或下单倍数");
        if(!"single-price".equals(input.path("policy").path("scope").asText()) || !"before-coefficient".equals(input.path("policy").path("calculation").asText()))throw AppException.conflict("FOB计算规则已变化，请刷新报价");
        var mode=input.path("displayMode").asText();if(!Set.of("tiers","quantity").contains(mode))throw AppException.unprocessable("FOB展示范围无效");
        var rows=JsonNodeFactory.instance.arrayNode(); var ranges=JsonNodeFactory.instance.arrayNode();
        var freight=amount(parsed.path("freight").path("unitFreightCny"));var tiers=parsed.path("priceTiers");
        if(!tiers.isArray() || tiers.isEmpty() || tiers.size()>100)throw AppException.unprocessable("FOB采购阶梯无效");
        long previous=0;ObjectNode selected=null;
        for(var tier:tiers) {
            long min=count(tier.path("minQty"));Long max=tier.path("maxQty").isNull()?null:count(tier.path("maxQty"));
            if(min<moq || min<=previous || max!=null&&max<min)throw AppException.unprocessable("FOB采购阶梯重叠或无效");previous=max==null?Long.MAX_VALUE:max;
            var purchase=amount(tier.path("unitPriceCny"));var included=purchase.multiply(new BigDecimal("1.1"));var cost=included.add(freight);
            var row=object().put("minQty",min).put("unit",text(tier.path("unit"),20,true)).put("purchaseCny",purchase).put("taxIncludedCny",included).put("freightCny",freight).put("costCny",cost)
                .put("declaredUsd",price(cost,0,"1.14",rate)).put("undeclaredUsd",price(cost,0,"1.1628",rate));
            if(max==null)row.putNull("maxQty");else row.put("maxQty",max);
            long first=((min+multiple-1)/multiple)*multiple;Long last=max==null?null:max/multiple*multiple;
            Long threshold=null;
            if(cost.signum()>0) {var target=BigDecimal.valueOf(200).divide(cost,0,RoundingMode.CEILING).max(BigDecimal.valueOf(min));if(target.compareTo(BigDecimal.valueOf(9007199254740991L-multiple))<=0){long n=((target.longValue()+multiple-1)/multiple)*multiple;if(max==null || n<=max)threshold=n;}}
            if(threshold==null)row.putNull("thresholdQty");else row.put("thresholdQty",threshold);rows.add(row);
            if(quantity>=min&&(max==null||quantity<=max))selected=row;
            if(last!=null&&first>last)continue;
            if(tiers.size()==1 && threshold!=null && threshold>first) {ranges.add(quote(row,first,threshold-multiple,rate,true));ranges.add(quote(row,threshold,last,rate,true));}
            else ranges.add(quote(row,first,last,rate,tiers.size()==1));
        }
        if(selected==null)throw AppException.unprocessable("此数量没有有效采购价");
        var current=quote(selected,quantity,quantity,rate,tiers.size()==1);
        if(mode.equals("quantity")){ranges.removeAll();ranges.add(current);}
        var sheet=validateSheet(input.path("sheet"),ranges,product.path("sku").asText());
        var snapshot=object().put("schemaVersion",1).put("rate",rate).put("quantity",quantity).put("displayMode",mode);
        snapshot.set("product",product);snapshot.set("policy",input.path("policy").deepCopy());snapshot.set("tiers",rows);snapshot.set("ranges",ranges);snapshot.set("current",current);snapshot.set("sheet",sheet);
        var payload=object().put("quoteMode","fob").put("customerName",text(request.path("customerName"),120,true).trim()).put("primarySku",product.path("sku").asText())
            .put("productCategory",product.path("category").asText()).put("productSummary",product.path("sku").asText()+" · FOB批发报价")
            .put("exchangeRate",rate).put("customQuoteQuantity",quantity).put("systemQuoteUsd",new BigDecimal(current.path("declaredUsd").asText()))
            .put("systemQuoteCny",new BigDecimal(current.path("declaredUsd").asText()).multiply(rate).setScale(2,RoundingMode.HALF_UP)).put("totalCostCny",amount(selected.path("costCny")));
        payload.putArray("quoteOptions");payload.set("fob",snapshot);return payload;
    }
    static ObjectNode validateSheet(JsonNode input,ArrayNode ranges,String sku) {
        if(!input.isObject())throw AppException.unprocessable("缺少FOB报价单");
        var sheet=object();for(var name:List.of("title","agent","date","whatsapp"))sheet.put(name,text(input.path(name),name.equals("title")?80:40,name.equals("date")));
        var labels=input.path("quantityLabels");var normal=List.of("With declaration","Without declaration");
        if(!labels.isArray()||labels.size()!=2||!new HashSet<>(List.of(labels.path(0).asText(),labels.path(1).asText())).equals(new HashSet<>(normal)))throw AppException.unprocessable("FOB价格列无效");
        sheet.set("quantityLabels",labels.deepCopy());sheet.put("priceGroupLabel","FOB Unit Price (USD)").put("showQuantityRange",true);
        var hidden=sheet.putArray("hiddenColumns");List.of("country","provider","shippingTime","processingTime").forEach(hidden::add);
        var columns=input.path("columnOrder");if(!columns.isArray()||columns.size()!=3)throw AppException.unprocessable("FOB列排序无效");
        var keys=new HashSet<String>();for(var c:columns)keys.add(c.asText());if(!keys.equals(Set.of("number","sku","prices")))throw AppException.unprocessable("FOB列排序无效");sheet.set("columnOrder",columns.deepCopy());
        var notes=input.path("notes");if(!notes.isArray()||notes.size()>20)throw AppException.unprocessable("报价说明无效");var savedNotes=sheet.putArray("notes");for(var note:notes)savedNotes.add(text(note,3000,false));
        var sourceRows=input.path("rows");if(!sourceRows.isArray()||sourceRows.size()!=ranges.size())throw AppException.conflict("报价阶梯已变化，请重新查询");
        var used=new HashSet<Integer>();var saved=sheet.putArray("rows");
        for(var r:sourceRows) {
            String key=r.path("key").asText();int index=-1;for(int i=0;i<ranges.size();i++)if(key.equals(sku+"-"+i))index=i;
            if(index<0||!used.add(index))throw AppException.unprocessable("FOB阶梯排序无效");var range=ranges.get(index);
            var prices=r.path("prices");if(!prices.isArray()||prices.size()!=2)throw AppException.unprocessable("FOB报价无效");
            for(int i=0;i<2;i++){var expected=new BigDecimal(range.path(labels.get(i).asText().equals(normal.get(0))?"declaredUsd":"undeclaredUsd").asText());if(amount(prices.get(i)).compareTo(expected)!=0)throw AppException.conflict("FOB价格与当前资料不一致，请重新查询");}
            var unit=range.path("unit").asText();if(unit.equals("件"))unit="pcs";
            String min=range.path("minQty").asText(),max=range.path("maxQty").asText();String label=range.path("maxQty").isNull()?min+"+":min.equals(max)?min:min+"–"+max;
            var row=saved.addObject().put("key",key).put("number",saved.size()).put("sku",sku).put("quantityRange",label+" "+unit).put("country","").put("provider","").put("shippingTime","").put("sourceDescription","");row.set("prices",prices.deepCopy());
        }
        sheet.putArray("issues");return sheet;
    }
}
