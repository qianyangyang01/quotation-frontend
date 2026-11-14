package com.milano.quotation.quote;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import java.math.BigDecimal;

/** Current request fixture; legacy calculation fixtures stay unchanged for saved-history tests. */
final class FobClassifiedTestData {
    static ObjectNode request(ObjectNode legacy) {
        var result=legacy.deepCopy();var root=(ObjectNode)result.path("fob");
        if(root.at("/product").has("classification"))return result;
        if(root.path("schemaVersion").asInt()==2){
            for(var item:root.path("items")) classify((ObjectNode)item);
            root.set("product",root.at("/items/0/product").deepCopy());
            ((ObjectNode)root.path("sheet")).putArray("quantityLabels").add("Unit Price");
            for(var row:root.at("/sheet/rows")){
                var key=row.path("key").asText();var split=key.indexOf(':');
                var item=root.path("items").get(Integer.parseInt(key.substring(0,split)));
                var originalItem=legacy.at("/fob/items").get(Integer.parseInt(key.substring(0,split)));
                for(int j=0;j<item.at("/sheet/rows").size();j++){
                    var source=item.at("/sheet/rows").get(j);
                    if(source.path("key").asText().equals(key.substring(split+1))){
                        var prices=row.path("prices").equals(originalItem.at("/sheet/rows").get(j).path("prices"))?source.path("prices").deepCopy():JsonNodeFactory.instance.arrayNode().add(row.path("prices").get(0));
                        ((ObjectNode)row).set("prices",prices);
                    }
                }
            }
        }else classify(root);
        return result;
    }
    private static void classify(ObjectNode snapshot){
        var product=(ObjectNode)snapshot.path("product");
        product.put("classification","rebate-fob");FobPricing.applyPurchase(product,product.deepCopy());
        var config=FobQuotation.object().put("version",-1);config.set("value",FobPricing.defaults());
        product.set("pricing",FobPricing.pricing(product,new BigDecimal(snapshot.path("rate").asText()),config));
        var row=product.at("/pricing/rows/0");var sheet=(ObjectNode)snapshot.path("sheet");sheet.putArray("quantityLabels").add("Unit Price");
        int index=0;for(var line:sheet.path("rows")){
            boolean extra="quantity".equals(snapshot.path("displayMode").asText())
                ? new BigDecimal(row.path("costCny").asText()).multiply(new BigDecimal(snapshot.path("quantity").asText())).compareTo(new BigDecimal("200"))<0
                : index==0&&sheet.path("rows").size()>1;
            var cost=new BigDecimal(row.path("costCny").asText());var rate=new BigDecimal(snapshot.path("rate").asText());
            var original=FobQuotation.price(cost,extra?1:0,"1.14",rate);
            var price=line.path("prices").get(0).decimalValue();
            if(price.compareTo(new BigDecimal(original))==0)price=new BigDecimal(row.path(extra?"smallOrderUsd":"declaredUsd").asText());
            ((ObjectNode)line).putArray("prices").add(price);index++;
        }
    }
    private FobClassifiedTestData(){}
}
