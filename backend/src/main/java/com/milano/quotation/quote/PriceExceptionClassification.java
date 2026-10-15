package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import com.milano.quotation.purchase.PurchaseProductRepository;
import com.milano.quotation.fob.FobPurchaseRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;

/** Review metadata only. Never updates the saved quotation or procurement data. */
@Service
public class PriceExceptionClassification {
    static final Set<String> CATEGORIES = Set.of("purchase-price", "channel-price", "entry-error", "other");
    private final PurchaseProductRepository purchases;
    private final FobPurchaseRepository fob;
    public PriceExceptionClassification(PurchaseProductRepository purchases, FobPurchaseRepository fob) {
        this.purchases=purchases; this.fob=fob;
    }
    static ObjectNode classification(JsonNode request) {
        var category=request.path("exceptionType");
        if(!category.isTextual() || !CATEGORIES.contains(category.asText()))
            throw AppException.unprocessable("请选择异常类型：采购价格异常、渠道价格异常、业务填写错误或其他/原因待核实");
        return JsonNodeFactory.instance.objectNode().put("category",category.asText()).put("recordedAt",Instant.now().toString());
    }
    ObjectNode capture(JsonNode request, JsonNode quote) {
        var result=classification(request);
        optionalExplanation(request,"note");
        var targets=result.putArray("targets");
        if(!result.path("category").asText().equals("purchase-price"))return result;
        var allowed=new LinkedHashMap<String,String>();
        if("fob".equals(quote.path("quoteMode").asText())) {
            var product=quote.at("/fob/product");
            add(allowed,product.path("sku").asText(),product.path("source").asText());
        } else if("bundle".equals(quote.path("quoteMode").asText())) {
            for(var item:quote.path("bundleItems"))add(allowed,item.path("sku").asText(),"standard");
        } else if(!ManualQuotation.isManual(quote)) add(allowed,quote.path("primarySku").asText(),"standard");
        var selected=new LinkedHashSet<String>();
        if(request.has("exceptionSkus")) {
            if(!request.path("exceptionSkus").isArray())throw AppException.unprocessable("异常SKU格式不正确");
            for(var item:request.path("exceptionSkus")) {
                if(!item.isTextual() || !allowed.containsKey(item.asText()) || !selected.add(item.asText()))
                    throw AppException.unprocessable("请选择当前报价中的异常SKU，不可重复或包含其他商品");
            }
        } else if(allowed.size()==1)selected.add(allowed.keySet().iterator().next());
        if(selected.isEmpty())throw AppException.unprocessable("采购价格异常必须明确选择对应的异常SKU");
        selected.forEach(sku->targets.add(target(sku,allowed.get(sku))));
        return result;
    }
    ObjectNode captureItem(JsonNode request, String sku, String source) {
        var result=classification(request);
        optionalExplanation(request,"reason");
        var targets=result.putArray("targets");
        if(result.path("category").asText().equals("purchase-price"))targets.add(target(sku,source));
        return result;
    }
    static String optionalExplanation(JsonNode request,String field) {
        if(!request.hasNonNull(field))return "";
        var value=request.path(field);
        if(!value.isTextual() || value.asText().length()>500)
            throw AppException.unprocessable("异常说明须为500字以内的文本，可不填写");
        return value.asText().trim();
    }
    private static void add(Map<String,String> allowed,String sku,String source) {
        if(sku.matches("[A-Z0-9][A-Z0-9._/-]{0,95}"))allowed.put(sku,source);
    }
    private ObjectNode target(String sku,String source) {
        var result=JsonNodeFactory.instance.objectNode().put("sku",sku).put("source",source);
        JsonNode payload=null;
        if("standard".equals(source))payload=purchases.findBySku(sku).map(p->p.payload).orElse(null);
        else if("fob".equals(source))payload=fob.findById(sku).map(p->p.payload).orElse(null);
        String owner=payload==null?"":payload.path("quotationOwner").asText("").trim();
        result.put("procurementOwner",owner).put("attribution",owner.isEmpty()?"unassigned":"assigned");
        if(payload!=null)result.put("dataSource",payload.path("dataSource").asText(source));
        return result;
    }
}
