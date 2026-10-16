package com.milano.quotation.purchase;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import static com.milano.quotation.purchase.PurchaseRankingService.*;
import static org.junit.jupiter.api.Assertions.*;

class PurchaseRankingServiceTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final Instant now=Instant.parse("2026-10-16T12:00:00Z");
    private JsonNode json(String value){return mapper.readTree(value);}
    private Product product(String sku,String owner){return new Product(sku,"standard",owner,json("""
        {"weightG":100,"minOrderQty":1,"purchasePriceCny":12,"singleFreightCny":0,"taxPoint":0}
        """));}
    private JsonNode refs(String... skus){var a=mapper.createArrayNode();for(var sku:skus)a.addObject().put("sku",sku).put("source","standard");return a;}
    private JsonNode exception(String sku,String owner){var e=mapper.createObjectNode().put("category","purchase-price");e.putArray("targets").addObject().put("sku",sku).put("source","standard").put("procurementOwner",owner);return e;}
    private Review review(String id,String date,JsonNode refs,JsonNode exception){var event=mapper.createObjectNode().put("at",date).put("action","complete").put("after","rejected");if(exception!=null)event.set("priceException",exception);return new Review(id,refs,event);}
    private Result report(List<Product> products,List<Review> reviews){return aggregate("all","standard",30,now.minusSeconds(30L*86400),now,"",true,Set.of(),products,reviews);}
    private Exceptions owner(Result r,String owner){return r.exceptions().stream().filter(x->x.owner().equals(owner)).findFirst().orElseThrow();}

    @Test void deduplicatesRepeatedReviewsAndKeepsCapturedOwnerAfterProcurementReassignment(){
        var first=review("q1","2026-10-02T00:00:00Z",refs("SKU1"),exception("SKU1","原采购"));
        var repeat=review("q1","2026-10-03T00:00:00Z",refs("SKU1"),exception("SKU1","新采购"));
        var result=report(List.of(product("SKU1","新采购")),List.of(repeat,first));
        assertEquals(1,owner(result,"原采购").count());assertEquals(100,owner(result,"原采购").rate());
        assertEquals(0,owner(result,"新采购").count());assertEquals(0,owner(result,"新采购").relatedQuotes());
    }
    @Test void bundleCountsOnlySelectedSkuAndQuoteRateUsesDistinctRecords(){
        var classified=mapper.createObjectNode().put("category","purchase-price");var targets=classified.putArray("targets");
        for(var sku:List.of("A","B"))targets.addObject().put("sku",sku).put("source","standard").put("procurementOwner","采购甲");
        var result=report(List.of(product("A","采购甲"),product("B","采购甲"),product("C","采购乙")),
                List.of(review("q1","2026-10-02T00:00:00Z",refs("A","B","C"),classified)));
        assertEquals(2,owner(result,"采购甲").count());assertEquals(1,owner(result,"采购甲").abnormalQuotes());
        assertEquals(1,owner(result,"采购甲").relatedQuotes());assertEquals(100,owner(result,"采购甲").rate());
        assertEquals(0,owner(result,"采购乙").count());assertEquals(1,owner(result,"采购乙").relatedQuotes());
    }
    @Test void excludesOtherCategoriesAndSeparatesUnclassifiedUnassignedAndUnknownDates(){
        var rows=List.of(review("other","2026-10-02T00:00:00Z",refs("A"),json("{\"category\":\"channel-price\"}")),
                review("old","2026-10-02T00:00:00Z",refs("A"),null),review("unknown","",refs("A"),null),
                review("missing","2026-10-02T00:00:00Z",refs("A"),exception("A","")),
                review("outside","2026-08-02T00:00:00Z",refs("A"),exception("A","采购甲")));
        var result=report(List.of(product("A","采购甲")),rows);
        assertEquals(0,owner(result,"采购甲").count());assertTrue(result.exceptions().stream().noneMatch(x->x.owner().isBlank()));
        assertEquals(1,result.unclassified());assertEquals(1,result.undated());
    }
    @Test void itemChangesOnlyCountsTheReviewedSkuAndIgnoresRetainedCopies(){
        var e=mapper.createObjectNode().put("action","fob-return").put("at","2026-10-02T00:00:00Z");
        e.putArray("itemChanges").addObject().put("index",1).put("status","rejected").set("priceException",exception("B","乙"));
        e.putArray("itemReviews").addObject().set("priceException",exception("A","甲"));
        var result=report(List.of(product("A","甲"),product("B","乙")),List.of(new Review("fob1",refs("A","B"),e)));
        assertEquals(0,owner(result,"甲").relatedQuotes());assertEquals(1,owner(result,"乙").count());
    }
    @Test void salesScopeMatchesExactSkuAndSourceAndReportsUnmatched(){
        var products=List.of(product("A","甲"),product("A-1","甲"),product("B","乙"));
        var result=aggregate("sales","standard",30,now.minusSeconds(30L*86400),now,"6—8月",true,Set.of("A","MISSING"),products,List.of());
        assertEquals(1,result.completion().size());assertEquals(1,result.completion().getFirst().total());assertEquals(1,result.unmatchedSalesSkus());
        assertEquals(100,result.completion().getFirst().rate());
    }
    @Test void zeroTaxAndZeroFreightAreCompleteButMissingTaxAndLegacyZeroPriceAreNot(){
        assertTrue(complete(product("A","甲")));
        assertFalse(complete(new Product("A","standard","甲",json("{\"weightG\":100,\"minOrderQty\":1,\"purchasePriceCny\":12,\"singleFreightCny\":0}"))));
        assertFalse(complete(new Product("A","standard","甲",json("{\"weightG\":100,\"minOrderQty\":1,\"purchasePriceCny\":0,\"singleFreightCny\":0,\"taxPoint\":0,\"dataSource\":\"legacy_2026\"}"))));
        assertFalse(complete(product("AUTO-1","甲")));
        assertTrue(complete(new Product("A","fob","甲",json("{\"weightRaw\":\"100g\",\"parsed\":{\"minOrderQty\":1,\"priceTiers\":[{}],\"freight\":{\"totalFreightCny\":0}}}"))));
    }
    @Test void combinedCatalogCountsEachSkuOnceAndDoesNotInventOwnershipForConflicts(){
        var fob= new Product("A","fob","甲",json("{\"weightRaw\":\"100g\",\"parsed\":{\"minOrderQty\":1,\"priceTiers\":[{}],\"freight\":{\"totalFreightCny\":0}}}"));
        var result=aggregate("all","all",30,now.minusSeconds(30L*86400),now,"",true,Set.of(),List.of(product("A","甲"),fob),List.of());
        assertEquals(1,result.completion().getFirst().total());assertEquals(1,result.completion().getFirst().complete());
        var conflict=aggregate("all","all",30,now.minusSeconds(30L*86400),now,"",true,Set.of(),
                List.of(product("A","甲"),new Product("A","fob","乙",json("{}"))),List.of());
        assertTrue(conflict.completion().isEmpty());
    }
    @Test void excludedFirstAnomalyCannotBeReassignedToLaterEligibleOwner(){
        var result=report(List.of(product("A","采购甲")),List.of(
            review("q1","2026-10-02T00:00:00Z",refs("A"),exception("A","")),
            review("q1","2026-10-03T00:00:00Z",refs("A"),exception("A","采购甲"))));
        assertEquals(0,owner(result,"采购甲").count());assertEquals(0,owner(result,"采购甲").relatedQuotes());
    }
    @Test void excludedOwnersDoNotContributeDiagnosticsOrCompletion(){
        var result=report(List.of(product("A","")),List.of(
            review("q1","2026-10-02T00:00:00Z",refs("A"),null),review("q2","",refs("A"),null)));
        assertTrue(result.completion().isEmpty());assertTrue(result.exceptions().isEmpty());
        assertEquals(0,result.unclassified());assertEquals(0,result.undated());
    }
    @Test void sourceFilterDoesNotCountSameSkuFromAnotherCatalog(){
        var classified=exception("A","FOB采购").deepCopy();((tools.jackson.databind.node.ObjectNode)classified.at("/targets/0")).put("source","fob");
        var result=report(List.of(product("A","普通采购")),List.of(review("fob","2026-10-02T00:00:00Z",json("[{\"sku\":\"A\",\"source\":\"fob\"}]"),classified)));
        assertEquals(0,result.exceptions().getFirst().count());assertEquals(0,result.exceptions().getFirst().relatedQuotes());
    }
    @Test void exceptionsOnlyDoNotReadCompletenessFieldsButKeepOwnershipConflictsAndZeroRows(){
        // The exception path needs SKU/source/owner only: absent completeness JSON must not be read.
        var metadataOnly=List.of(new Product("A","standard","甲",null),new Product("A","fob","甲",null),
                new Product("B","standard","乙",null),new Product("B","fob","丙",null));
        var result=aggregate("sales","all",30,now.minusSeconds(30L*86400),now,"",true,
                Set.of("A","B","MISSING"),metadataOnly,List.of(),false);
        assertTrue(result.completion().isEmpty());
        assertEquals(List.of(new Exceptions("甲",0,0,0,0)),result.exceptions());
        assertEquals(1,result.unmatchedSalesSkus());
    }
}
