package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class FobQuotationTest {
    static ObjectNode input() {
        return (ObjectNode)new ObjectMapper().readTree("""
          {"quoteMode":"fob","customerName":"FOB客户","fob":{"schemaVersion":1,"rate":6.7,"quantity":26,"displayMode":"tiers",
           "policy":{"scope":"single-price","calculation":"before-coefficient"},
           "product":{"sku":"FOB-SAVE","source":"fob","updatedAt":"2026-10-09T00:00:00Z","category":"内裤","weight":"60 g","notices":[],
             "parsed":{"minOrderQty":1,"orderMultiple":1,"freight":{"quantity":100,"totalFreightCny":0,"unitFreightCny":0,"basis":"包邮","estimated":false},
               "priceTiers":[{"minQty":1,"maxQty":null,"unitPriceCny":7.2,"unit":"件"}]}},
           "sheet":{"title":"JerryFulfillment Quote Sheet","agent":"销售","date":"9 Oct 2026","whatsapp":"","columnOrder":["number","sku","prices"],
             "quantityLabels":["With declaration","Without declaration"],"notes":["编辑后的说明"],"rows":[
              {"key":"FOB-SAVE-0","prices":[1.69,1.72]},{"key":"FOB-SAVE-1","prices":[1.35,1.37]}]}}}
          """);
    }
    static ObjectNode calculate(ObjectNode input) {return FobQuotation.calculate(input,(ObjectNode)input.path("fob").path("product"),new BigDecimal("6.7"));}
    @Test void savesExactSurchargeRangesAndCurrentPriceIndependentOfChannelRules() {
        var out=calculate(input());
        assertEquals("fob",out.path("quoteMode").asText());assertTrue(out.path("quoteOptions").isEmpty());assertFalse(out.has("country"));
        assertEquals(25,out.at("/fob/ranges/0/maxQty").asInt());assertEquals(26,out.at("/fob/ranges/1/minQty").asInt());
        assertEquals("1.35",out.at("/fob/current/declaredUsd").asText());assertEquals("1.37",out.at("/fob/current/undeclaredUsd").asText());
        assertEquals("编辑后的说明",out.at("/fob/sheet/notes/0").asText());assertEquals("1–25 pcs",out.at("/fob/sheet/rows/0/quantityRange").asText());
    }
    @Test void preservesRowAndPriceColumnOrderWithoutChangingPriceMeaning() {
        var in=input();var sheet=(ObjectNode)in.at("/fob/sheet");
        sheet.putArray("columnOrder").add("prices").add("sku").add("number");
        sheet.putArray("quantityLabels").add("Without declaration").add("With declaration");
        var rows=sheet.putArray("rows");rows.addObject().put("key","FOB-SAVE-1").putArray("prices").add(1.37).add(1.35);rows.addObject().put("key","FOB-SAVE-0").putArray("prices").add(1.72).add(1.69);
        var out=calculate(in);assertEquals("26+ pcs",out.at("/fob/sheet/rows/0/quantityRange").asText());assertEquals(1.37,out.at("/fob/sheet/rows/0/prices/0").asDouble());
    }
    @Test void rejectsForgedPricesMissingRowsDuplicateRowsInvalidQuantityAndPolicy() {
        var forged=input();((ArrayNode)forged.at("/fob/sheet/rows/0/prices")).set(0,DoubleNode.valueOf(.01));assertThrows(RuntimeException.class,()->calculate(forged));
        var missing=input();((ArrayNode)missing.at("/fob/sheet/rows")).remove(0);assertThrows(RuntimeException.class,()->calculate(missing));
        var duplicate=input();((ObjectNode)duplicate.at("/fob/sheet/rows/1")).put("key","FOB-SAVE-0");assertThrows(RuntimeException.class,()->calculate(duplicate));
        for(double n:new double[]{0,-1,1.5,1e20}) {var invalid=input();((ObjectNode)invalid.path("fob")).put("quantity",n);assertThrows(RuntimeException.class,()->calculate(invalid));}
        var policy=input();((ObjectNode)policy.at("/fob/policy")).put("scope","all");assertThrows(RuntimeException.class,()->calculate(policy));
    }
    @Test void quantityOnlyRetainsSmallOrderFeeAndMultipleConstraints() {
        var in=input();((ObjectNode)in.path("fob")).put("quantity",2).put("displayMode","quantity");((ArrayNode)in.at("/fob/sheet/rows")).remove(1);
        var out=calculate(in);assertEquals("1.69",out.at("/fob/current/declaredUsd").asText());assertEquals(1,out.at("/fob/ranges").size());
        ((ObjectNode)in.at("/fob/product/parsed")).put("orderMultiple",10);assertThrows(RuntimeException.class,()->calculate(in));
    }
    @Test void newProcurementHasIndependentTierAndHundredPieceFreightMapping() {
        var raw=(ObjectNode)new ObjectMapper().readTree("""
          {"dataSource":"standard","catalogState":"ready","skuOrigin":"imported","minOrderQty":2,"purchasePriceCny":26,"tier2MinQty":50,"tier2PriceCny":25,"tier3MinQty":100,"tier3PriceCny":24,"freight100Cny":25}
          """);
        var parsed=FobQuotation.standard(raw);assertEquals(3,parsed.path("priceTiers").size());assertEquals(49,parsed.at("/priceTiers/0/maxQty").asInt());assertEquals(.25,parsed.at("/freight/unitFreightCny").asDouble());
        raw.put("purchasePriceBasis","tax_included");assertThrows(RuntimeException.class,()->FobQuotation.standard(raw));
        raw.remove("purchasePriceBasis");raw.remove("freight100Cny");assertThrows(RuntimeException.class,()->FobQuotation.standard(raw));
    }
}
