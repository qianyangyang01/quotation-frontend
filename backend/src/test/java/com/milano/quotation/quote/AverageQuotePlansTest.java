package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.*;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class AverageQuotePlansTest {
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode record() {
        var record = (ObjectNode) mapper.readTree("""
          {"id":"r","customQuoteQuantity":3,"quoteConfirmed":true,
           "quoteOptions":[
             {"id":"a","country":"美国","countryCode":"US","quote1Usd":5,"quote2Usd":5.95,"quote3Usd":7},
             {"id":"b","country":"美国","countryCode":"US","quote1Usd":5.25,"quote2Usd":6.25,"quote3Usd":7},
             {"id":"c","country":"美国","countryCode":"US","quote1Usd":5.3,"quote2Usd":6.45,"quote3Usd":7.45}],
           "customerQuote":{"quantities":[1,2,3],"rows":[
             {"optionId":"a","prices":[5,5.95,7]}, {"optionId":"b","prices":[5.25,6.25,7]}, {"optionId":"c","prices":[5.3,6.45,7.45]}],
           "averagePlans":[{"id":"p","mode":"weighted","display":"details","provider":"Combined Shipping","shippingTime":"7-12 workingdays","quantities":[1,2,3],
             "members":[{"optionId":"a","weight":50,"sourcePrices":[5,5.95,7]},{"optionId":"b","weight":30,"sourcePrices":[5.25,6.25,7]},{"optionId":"c","weight":20,"sourcePrices":[5.3,6.45,7.45]}],
             "systemPrices":[5.14,6.14,7.09],"prices":[5.14,6.14,7.09]}]}}
          """);
        return record;
    }
    ObjectNode plan(ObjectNode r) { return (ObjectNode) r.path("customerQuote").path("averagePlans").get(0); }
    ObjectNode member(ObjectNode r) { return (ObjectNode) plan(r).path("members").get(0); }
    ObjectNode patch(ObjectNode r) { return mapper.createObjectNode().set("customerQuote", r.path("customerQuote").deepCopy()); }

    @Test void persistsCanonicalWeightedAndEqualSnapshotsWithIndependentTiers() {
        var r = record(); CustomerQuotePrices.initialize(r);
        var restored = (ObjectNode) mapper.readTree(r.toString());
        assertEquals(r, restored);
        assertEquals("[5.14,6.14,7.09]", plan(restored).path("systemPrices").toString());
        assertEquals(restored.path("customerQuote"), restored.path("sheetQuote"));
        assertFalse(restored.path("systemQuantityQuotes").has("averagePlans"));
        var equal = record(); plan(equal).put("mode", "equal");
        plan(equal).set("systemPrices", mapper.readTree("[5.18,6.22,7.15]"));
        CustomerQuotePrices.initialize(equal);
        assertEquals(1, member(equal).path("weight").asDouble());
    }
    @Test void rejectsBadWeightsForgedTotalsDuplicateMissingHiddenAndMixedScopeSources() {
        List<Consumer<ObjectNode>> mutations = List.of(
            r -> member(r).put("weight", 49), r -> member(r).put("weight", 0), r -> member(r).put("weight", 50.001),
            r -> ((ArrayNode) member(r).path("sourcePrices")).set(0, DoubleNode.valueOf(0)),
            r -> ((ArrayNode) plan(r).path("systemPrices")).set(0, DoubleNode.valueOf(0)),
            r -> member(r).put("optionId", "b"), r -> member(r).put("optionId", "foreign"),
            r -> ((ObjectNode) r.path("quoteOptions").get(0)).putNull("quote2Usd"),
            r -> ((ObjectNode) r.path("quoteOptions").get(0)).put("available", false),
            r -> ((ObjectNode) r.path("quoteOptions").get(0)).put("countryCode", "GB"),
            r -> ((ObjectNode) r.path("quoteOptions").get(0)).put("quoteRegion", "remote"),
            r -> ((ObjectNode) r.path("quoteOptions").get(0)).put("taxRatePercent", 5),
            r -> ((ObjectNode) r.path("customerQuote")).putArray("hiddenOptionIds").add("a"),
            r -> plan(r).putArray("quantities").add(3).add(2).add(3));
        for (var mutation : mutations) { var r = record(); mutation.accept(r); assertThrows(RuntimeException.class, () -> CustomerQuotePrices.initialize(r)); }
    }
    @Test void editsOnlyExplicitCustomerPlanWhileInitialAndSystemSnapshotsStayImmutable() {
        var r = record(); CustomerQuotePrices.initialize(r);
        var original = r.deepCopy(); var p = patch(r);
        ((ArrayNode) plan(p).path("prices")).set(0, DoubleNode.valueOf(4.8));
        CustomerQuotePrices.preparePatch(r, p);
        assertTrue(QuotationFinanceReview.pricesChanged(r, p));
        QuotationConfirmation.prepare(r, p); assertFalse(p.path("quoteConfirmed").asBoolean(true));
        assertEquals(original, r); assertFalse(p.has("systemQuantityQuotes")); assertFalse(p.has("sheetQuote"));
        var oldClient = patch(r); ((ObjectNode) oldClient.path("customerQuote")).remove("averagePlans");
        CustomerQuotePrices.preparePatch(r, oldClient); assertEquals(r.path("customerQuote"), oldClient.path("customerQuote"));
        assertFalse(QuotationFinanceReview.pricesChanged(r, oldClient));
        var remove = patch(r); ((ObjectNode) remove.path("customerQuote")).putArray("averagePlans");
        CustomerQuotePrices.preparePatch(r, remove); assertTrue(QuotationFinanceReview.pricesChanged(r, remove));
    }
    @Test void changingPresentationDoesNotResetReviewButChangingWeightsDoes() {
        var r = record(); CustomerQuotePrices.initialize(r); var p = patch(r);
        plan(p).put("display", "summary").put("shippingTime", "8-12 workingdays");
        CustomerQuotePrices.preparePatch(r, p); assertFalse(QuotationFinanceReview.pricesChanged(r, p));
        QuotationConfirmation.prepare(r, p); assertFalse(p.has("quoteConfirmed"));
        var changed = patch(r); member(changed).put("weight", 30);
        ((ObjectNode) plan(changed).path("members").get(1)).put("weight", 50);
        plan(changed).set("systemPrices", mapper.readTree("[5.19,6.20,7.09]"));
        CustomerQuotePrices.preparePatch(r, changed); assertTrue(QuotationFinanceReview.pricesChanged(r, changed));
    }
    @Test void preservesQuantityIdentityAndUsesSavedSystemSnapshotForCustomTiers() {
        var r = record(); CustomerQuotePrices.initialize(r); var p = patch(r);
        plan(p).set("quantities", mapper.readTree("[3,1,2]"));
        for (var field : List.of("systemPrices", "prices")) plan(p).set(field, mapper.readTree("[7.09,5.14,6.14]"));
        for (var m : plan(p).path("members")) {
            var prices = m.path("sourcePrices");
            ((ObjectNode) m).putArray("sourcePrices").add(prices.get(2)).add(prices.get(0)).add(prices.get(1));
        }
        CustomerQuotePrices.preparePatch(r, p); assertFalse(QuotationFinanceReview.pricesChanged(r, p));
        ((ObjectNode) r.path("quoteOptions").get(0)).put("quote1Usd", 999);
        CustomerQuotePrices.preparePatch(r, p); // Read existing saved system values, not a changed route price.
    }
}
