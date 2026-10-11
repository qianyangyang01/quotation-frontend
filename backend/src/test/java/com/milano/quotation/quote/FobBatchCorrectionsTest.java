package com.milano.quotation.quote;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class FobBatchCorrectionsTest {
    @Test void numericRepresentationAndUnknownPolicyFieldsCannotBypassUnchangedGuard() {
        var before=FobQuotationTest.calculate(FobQuotationTest.input()).path("fob");var after=(ObjectNode)before.deepCopy();
        after.put("rate",6.7);after.put("quantity",26);((ObjectNode)after.path("policy")).put("unknown",true);
        assertFalse(FobBatchCorrections.sourceChanged(before,after));
        after.put("rate",new BigDecimal("6.700"));assertFalse(FobBatchCorrections.sourceChanged(before,after));
        after.put("quantity",27);assertTrue(FobBatchCorrections.sourceChanged(before,after));
    }
    @Test void mergedPresentationRetainsRowAndPriceOrderAndRefreshesMultiples() {
        var input=FobBatchQuotationTest.input(2);var root=(ObjectNode)FobBatchQuotationTest.service(input).prepare(input).path("fob");
        var sheet=(ObjectNode)root.path("sheet");var originals=sheet.path("rows").deepCopy();
        sheet.putArray("quantityLabels").add("Without declaration").add("With declaration");
        var rows=sheet.putArray("rows");for(int i=originals.size()-1;i>=0;i--)rows.add(originals.get(i));
        sheet.putArray("notes").add("Customer custom note").add("SKU0: Order in multiples of 2.");
        ((ObjectNode)root.at("/items/0/product/parsed")).put("orderMultiple",5);
        FobBatchCorrections.rebuildSheet(root);
        assertEquals("1:SKU1-1",root.at("/sheet/rows/0/key").asText());assertEquals(1.37,root.at("/sheet/rows/0/prices/0").asDouble());assertEquals(1.35,root.at("/sheet/rows/0/prices/1").asDouble());
        assertTrue(root.at("/sheet/notes").toString().contains("Customer custom note"));assertTrue(root.at("/sheet/notes").toString().contains("multiples of 5"));assertFalse(root.at("/sheet/notes").toString().contains("multiples of 2"));
        var stable=root.deepCopy();FobBatchCorrections.rebuildSheet(root);assertEquals(stable,root);
    }
    @Test void explicitContentEditsInvalidateApprovalsButKeepOutstandingReturnReasons() {
        var state=JsonNodeFactory.instance.objectNode();var items=state.putArray("fobItemReviews");
        items.addObject().put("index",0).put("status","approved").put("reviewedBy","admin");
        items.addObject().put("index",1).put("status","rejected").put("reason","核实采购运费");
        QuotationReviewService.invalidateFobApprovals(state);
        assertEquals("pending",state.at("/fobItemReviews/0/status").asText());assertFalse(state.at("/fobItemReviews/0").has("reviewedBy"));
        assertEquals("rejected",state.at("/fobItemReviews/1/status").asText());assertEquals("核实采购运费",state.at("/fobItemReviews/1/reason").asText());
    }
    @Test void duplicateItemsAndMalformedIndexesAreRejected() {
        var input=FobBatchQuotationTest.input(2);var root=(ObjectNode)FobBatchQuotationTest.service(input).prepare(input).path("fob");
        ((ArrayNode)root.path("items")).set(1,root.path("items").get(0).deepCopy());assertThrows(RuntimeException.class,()->FobBatchCorrections.rebuildSheet(root));
        var entries=JsonNodeFactory.instance.arrayNode();entries.addObject().put("index",0);entries.addObject().put("index",0);assertThrows(RuntimeException.class,()->FobBatchCorrections.indexes(entries,2));
        entries.remove(1);((ObjectNode)entries.get(0)).put("index",1.5);assertThrows(RuntimeException.class,()->FobBatchCorrections.indexes(entries,2));
    }
}
