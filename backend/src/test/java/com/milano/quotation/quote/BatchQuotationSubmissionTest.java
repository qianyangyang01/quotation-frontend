package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BatchQuotationSubmissionTest {
    static ObjectNode request(ObjectNode input) {
        var body=JsonNodeFactory.instance.objectNode().put("kind","fob");
        body.set("customerName",input.path("customerName")); body.set("fob",input.path("fob")); return body;
    }
    @Test void fourteenFobSkusProduceFourteenSingleSnapshotsWithOriginalPresentation() {
        var input=FobBatchQuotationTest.input(14);
        var rows=(ArrayNode)input.at("/fob/sheet/rows");
        var reversed=new ArrayList<tools.jackson.databind.JsonNode>(); rows.forEach(reversed::add); Collections.reverse(reversed); rows.removeAll(); reversed.forEach(rows::add);
        var before=input.deepCopy();
        var service=new BatchQuotationSubmission(mock(BatchSingleQuotation.class),FobBatchQuotationTest.service(input));
        var out=service.prepare(request(input)); assertEquals(14,out.size()); assertEquals(before,input);
        for(int i=0;i<14;i++) {
            var row=out.get(i);assertEquals("SKU"+i,row.path("primarySku").asText());assertEquals(1,row.at("/fob/schemaVersion").asInt());
            assertFalse(row.path("fob").has("items"));assertFalse(row.at("/fob/sheet").has("fobBatch"));
            assertEquals("SKU"+i+"-1",row.at("/fob/sheet/rows/0/key").asText());
            assertEquals("编辑后的说明",row.at("/fob/sheet/notes/0").asText());assertTrue(row.at("/fob/sourceSnapshot").isObject());
        }
    }
    static ObjectNode repeatedQuantities() {
        var in=FobBatchQuotationTest.input(2);var root=(ObjectNode)in.path("fob");root.put("displayMode","quantity");
        var merged=((ObjectNode)root.path("sheet")).putArray("rows");
        for(int i=0;i<2;i++) {
            var item=(ObjectNode)root.path("items").get(i);item.put("quantity",i==0?2:26).put("displayMode","quantity");
            ((ObjectNode)item.path("product")).put("sku","SKU0");
            var source=(ObjectNode)item.at("/sheet/rows/"+i).deepCopy();source.put("key","SKU0-0");
            ((ObjectNode)item.path("sheet")).putArray("rows").add(source);
            var copy=source.deepCopy();copy.put("key",i+":SKU0-0");merged.add(copy);
        }
        return in;
    }
    @Test void sameSkuDifferentQuantitiesRemainOneRecordWithBothActualPrices() {
        var in=repeatedQuantities();var service=new BatchQuotationSubmission(mock(BatchSingleQuotation.class),FobBatchQuotationTest.service(in));
        var out=service.prepare(request(in)); assertEquals(1,out.size());var snapshot=out.get(0).path("fob");
        assertEquals(1,snapshot.path("schemaVersion").asInt());assertEquals("[2,26]",snapshot.path("quantities").toString());
        assertEquals("1.52",snapshot.at("/ranges/0/declaredUsd").asText());assertEquals("1.35",snapshot.at("/ranges/1/declaredUsd").asText());
        assertEquals(2,snapshot.at("/sheet/rows").size());
    }
    @Test void rejectsTamperingAndSameSkuWithMixedSources() {
        var in=repeatedQuantities();var service=new BatchQuotationSubmission(mock(BatchSingleQuotation.class),FobBatchQuotationTest.service(in));
        ((ArrayNode)in.at("/fob/sheet/rows/1/prices")).set(0,DoubleNode.valueOf(.01));assertThrows(RuntimeException.class,()->service.prepare(request(in)));
        var snapshot=repeatedQuantities().path("fob");((ObjectNode)snapshot.at("/items/1/product")).put("source","standard");
        assertThrows(RuntimeException.class,()->BatchQuotationSubmission.splitFob(snapshot));
    }
}
