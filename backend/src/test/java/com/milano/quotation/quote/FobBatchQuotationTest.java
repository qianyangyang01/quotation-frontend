package com.milano.quotation.quote;

import com.milano.quotation.fob.FobPurchaseService;
import com.milano.quotation.purchase.PurchaseProductService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FobBatchQuotationTest {
    static ObjectNode input(int count) {
        var input=FobQuotationTest.input();var root=(ObjectNode)input.path("fob");root.put("schemaVersion",2);
        var template=FobQuotationTest.input().path("fob");var items=root.putArray("items");var rows=((ObjectNode)root.path("sheet")).putArray("rows");
        for(int i=0;i<count;i++) {
            var item=(ObjectNode)template.deepCopy();var sku="SKU"+i;((ObjectNode)item.path("product")).put("sku",sku);
            for(int j=0;j<item.at("/sheet/rows").size();j++) {var row=(ObjectNode)item.at("/sheet/rows/"+j);row.put("key",sku+"-"+j);var merged=row.deepCopy();merged.put("key",i+":"+sku+"-"+j);rows.add(merged);}
            items.add(item);
        }
        return input;
    }
    static FobQuotation service(ObjectNode input) {
        var source=mock(FobPurchaseService.class);var q=spy(new FobQuotation(source,mock(PurchaseProductService.class),null,null));
        doReturn(new BigDecimal("6.7")).when(q).currentRate();
        for(var item:input.at("/fob/items")) {var p=(ObjectNode)item.path("product").deepCopy();p.put("weightRaw","60g");when(source.getForQuotation(p.path("sku").asText())).thenReturn(p);}
        return q;
    }
    @Test void twentySkusSaveAsOneCanonicalSnapshotWithoutMutatingInputs() {
        var in=input(20);var before=in.deepCopy();var out=service(in).prepare(in);
        assertEquals(2,out.at("/fob/schemaVersion").asInt());assertEquals(20,out.at("/fob/items").size());assertEquals(40,out.at("/fob/sheet/rows").size());
        assertEquals("19:SKU19-1",out.at("/fob/sheet/rows/39/key").asText());assertEquals(40,out.at("/fob/sheet/rows/39/number").asInt());
        assertEquals("1.52",out.at("/fob/items/0/ranges/0/declaredUsd").asText());assertTrue(out.at("/fob/items/0/sourceSnapshot").isObject());assertFalse(out.path("fob").has("current"));assertEquals(before,in);
        assertTrue(out.path("productSummary").asText().contains("SKU19"));
    }
    @Test void rejectsTamperedMergedPricesMissingAndDuplicateRows() {
        var bad=input(2);((ObjectNode)bad.at("/fob/sheet/rows/3")).putArray("prices").add(.01).add(.02);assertThrows(RuntimeException.class,()->service(bad).prepare(bad));
        var missing=input(2);((ArrayNode)missing.at("/fob/sheet/rows")).remove(0);assertThrows(RuntimeException.class,()->service(missing).prepare(missing));
        var duplicate=input(2);((ObjectNode)duplicate.at("/fob/sheet/rows/3")).put("key","0:SKU0-0");assertThrows(RuntimeException.class,()->service(duplicate).prepare(duplicate));
    }
    @Test void rejectsStaleProductVersionRateAndNestedBatch() {
        var in=input(2);var q=service(in);((ObjectNode)in.at("/fob/items/1/product")).put("updatedAt","old");assertTrue(assertThrows(RuntimeException.class,()->q.prepare(in)).getMessage().contains("SKU1"));
        var rate=input(2);((ObjectNode)rate.at("/fob/items/1")).put("rate",7);assertThrows(RuntimeException.class,()->service(rate).prepare(rate));
        var nested=input(2);((ObjectNode)nested.at("/fob/items/1")).put("schemaVersion",2);assertThrows(RuntimeException.class,()->service(nested).prepare(nested));
    }
    @Test void rejectsEmptyOversizeAndDuplicateProductRequests() {
        var empty=input(0);assertThrows(RuntimeException.class,()->service(empty).prepare(empty));
        var big=input(101);assertThrows(RuntimeException.class,()->service(big).prepare(big));
        var dupe=input(2);((ArrayNode)dupe.at("/fob/items")).set(1,dupe.at("/fob/items/0").deepCopy());assertThrows(RuntimeException.class,()->service(dupe).prepare(dupe));
    }
    @Test void acceptsOneHundredItemsAndRetainsLastPrice() {
        var in=input(100);var out=service(in).prepare(in);
        assertEquals(100,out.at("/fob/items").size());assertEquals(200,out.at("/fob/sheet/rows").size());
        assertEquals(out.at("/fob/items/99/sheet/rows/1/prices"),out.at("/fob/sheet/rows/199/prices"));
    }
    @Test void cannotEraseWonDealDetailsByOmittingStatus() {
        var current=input(2).put("status","won");var patch=JsonNodeFactory.instance.objectNode();patch.putArray("dealLines");
        assertThrows(RuntimeException.class,()->FobQuotation.validateBatchDeal(current,patch));
    }
}
