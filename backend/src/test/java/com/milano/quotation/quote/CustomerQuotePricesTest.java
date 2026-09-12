package com.milano.quotation.quote;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class CustomerQuotePricesTest {
    final ObjectMapper mapper=new ObjectMapper();
    ObjectNode record() { return (ObjectNode)mapper.readTree("""
        {"id":"r","customQuoteQuantity":4,"quoteOptions":[{"id":"a","quote1Usd":2,"quote2Usd":3,"quoteCustomUsd":5}],
         "customerQuote":{"quantities":[1,2,4],"rows":[{"optionId":"a","prices":[1.8,2.7,4.6]}]},
         "systemQuantityQuotes":{"quantities":[1,2,4],"rows":[{"optionId":"a","prices":[999,999,999]}]}}
        """); }
    @Test void canonicalSystemIsImmutableAndSheetStaysOriginalWhenRecordPricesChange() {
        var r=record();CustomerQuotePrices.initialize(r);
        assertEquals("[2,3,5]",r.path("systemQuantityQuotes").path("rows").get(0).path("prices").toString());
        assertEquals(r.path("customerQuote"),r.path("sheetQuote"));
        var patch=(ObjectNode)mapper.readTree("""
          {"customerQuote":{"quantities":[1,2,4],"rows":[{"optionId":"a","prices":[1.8,2.6,4.6]}]}}
          """);
        CustomerQuotePrices.preparePatch(r,patch);r.set("customerQuote",patch.get("customerQuote"));
        assertEquals(2.7,r.path("sheetQuote").path("rows").get(0).path("prices").get(1).asDouble());
        assertEquals(2.6,r.path("customerQuote").path("rows").get(0).path("prices").get(1).asDouble());
        for(var field:new String[]{"systemQuantityQuotes","sheetQuote","quoteOptions","systemQuoteUsd"}) {
            var bad=mapper.createObjectNode();bad.put(field,1);assertThrows(RuntimeException.class,()->CustomerQuotePrices.preparePatch(r,bad));
        }
    }
    @Test void rejectsInvalidQuantitiesPricesMissingAndForeignRoutes() {
        var r=record();
        for(var json:new String[]{
          "{\"quantities\":[2,2],\"rows\":[{\"optionId\":\"a\",\"prices\":[1,2]}]}",
          "{\"quantities\":[-1],\"rows\":[{\"optionId\":\"a\",\"prices\":[1]}]}",
          "{\"quantities\":[1],\"rows\":[{\"optionId\":\"a\",\"prices\":[1.234]}]}",
          "{\"quantities\":[1],\"rows\":[{\"optionId\":\"a\",\"prices\":[-1]}]}",
          "{\"quantities\":[1],\"rows\":[{\"optionId\":\"foreign\",\"prices\":[1]}]}",
          "{\"quantities\":[1],\"rows\":[]}",
          "{\"quantities\":[1],\"rows\":[{\"optionId\":\"a\",\"prices\":[\"2\"]}]}"
        }) assertThrows(RuntimeException.class,()->CustomerQuotePrices.validate(r,mapper.readTree(json)));
    }
    @Test void allowsBlankZeroAndNewQuantitiesButDoesNotInventTheirSystemBaseline() {
        var r=record();r.remove("systemQuantityQuotes");
        r.set("customerQuote",mapper.readTree("{\"quantities\":[1,2,50],\"rows\":[{\"optionId\":\"a\",\"prices\":[0,null,99]}]}"));
        CustomerQuotePrices.initialize(r);
        assertTrue(r.path("systemQuantityQuotes").path("rows").get(0).path("prices").get(2).isNull());
        assertEquals(0,r.path("customerQuote").path("rows").get(0).path("prices").get(0).asInt());
    }
}
