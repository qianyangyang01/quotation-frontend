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

    @Test void savesInterleavedRowOrderThroughRoundTripWithoutChangingPricesReviewOrOriginalSheet() {
        var r = record();
        ((ObjectNode) r.path("customerQuote")).putArray("rowOrder").add("average:p").add("option:c").add("option:a").add("option:b");
        CustomerQuotePrices.initialize(r);
        r = (ObjectNode) mapper.readTree(r.toString());
        assertEquals(r.path("customerQuote").path("rowOrder"), r.path("sheetQuote").path("rowOrder"));
        assertFalse(r.path("systemQuantityQuotes").has("rowOrder"));
        var before = r.deepCopy(); var p = patch(r);
        ((ObjectNode) p.path("customerQuote")).putArray("rowOrder").add("option:a").add("average:p").add("option:c").add("option:b");
        CustomerQuotePrices.preparePatch(r, p);
        assertFalse(QuotationFinanceReview.pricesChanged(r, p));
        QuotationConfirmation.prepare(r, p); assertFalse(p.has("quoteConfirmed"));
        assertEquals(before, r);
        assertEquals(r.path("customerQuote").path("rows"), p.path("customerQuote").path("rows"));
        assertEquals(plan(r), plan(p));
        var oldClient = patch(r); ((ObjectNode) oldClient.path("customerQuote")).remove("rowOrder");
        CustomerQuotePrices.preparePatch(r, oldClient);
        assertEquals(r.path("customerQuote"), oldClient.path("customerQuote"));
        var removed = patch(r); ((ObjectNode) removed.path("customerQuote")).remove("rowOrder");
        ((ObjectNode) removed.path("customerQuote")).putArray("averagePlans");
        CustomerQuotePrices.preparePatch(r, removed);
        assertEquals("[\"option:c\",\"option:a\",\"option:b\"]", removed.path("customerQuote").path("rowOrder").toString());
    }

    @Test void rejectsDuplicateForeignAndMalformedRowOrdersAndKeepsLegacyDefault() {
        var legacy = record(); CustomerQuotePrices.initialize(legacy);
        assertFalse(legacy.path("customerQuote").has("rowOrder"));
        for (var json : new String[]{"null", "{}", "[1]", "[\"option:foreign\"]", "[\"average:foreign\"]", "[\"option:a\",\"option:a\"]"}) {
            var r = record(); ((ObjectNode) r.path("customerQuote")).set("rowOrder", mapper.readTree(json));
            assertThrows(RuntimeException.class, () -> CustomerQuotePrices.initialize(r));
            var p = patch(legacy); ((ObjectNode) p.path("customerQuote")).set("rowOrder", mapper.readTree(json));
            assertThrows(RuntimeException.class, () -> CustomerQuotePrices.preparePatch(legacy, p));
        }
    }

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
    @Test void savesMixedTaxModesAndRegionsInOneCountryAndRestoresImmutableAverageSnapshots() {
        for (var mode : List.of("equal", "weighted")) {
            var r = record();
            ((ObjectNode) r.path("quoteOptions").get(0)).put("taxFeeMode", "fixed-order").put("taxIncluded", false).put("taxConfigured", true);
            var included = (ObjectNode) r.path("quoteOptions").get(1);
            included.put("taxFeeMode", "exempt").put("taxIncluded", true).put("taxConfigured", true).put("country", "US");
            included.remove("countryCode");
            ((ObjectNode) r.path("quoteOptions").get(2)).put("taxFeeMode", "per-item").put("taxRatePercent", 5).put("quoteRegion", "remote");
            plan(r).put("mode", mode);
            if (mode.equals("equal")) {
                plan(r).set("systemPrices", mapper.readTree("[5.18,6.22,7.15]"));
                plan(r).set("prices", mapper.readTree("[5.18,6.22,7.15]"));
            }
            var sources = r.path("quoteOptions").deepCopy();
            CustomerQuotePrices.initialize(r);
            assertEquals(3, plan(r).path("members").size());
            assertEquals(sources, r.path("quoteOptions"));
            var restored = (ObjectNode) mapper.readTree(r.toString());
            var before = restored.deepCopy(); var p = patch(restored);
            CustomerQuotePrices.preparePatch(restored, p);
            assertEquals(plan(restored), plan(p));
            assertEquals(before, restored);
            assertEquals(restored.path("customerQuote"), restored.path("sheetQuote"));
            assertFalse(QuotationFinanceReview.pricesChanged(restored, p));
        }
    }
    @Test void hidesSourcePresentationWithoutChangingPlanPricesHistoryOrReview() {
        var r = record(); CustomerQuotePrices.initialize(r);
        var original = r.deepCopy(); var p = patch(r);
        ((ObjectNode) p.path("customerQuote")).putArray("hiddenOptionIds").add("a").add("b").add("c");
        CustomerQuotePrices.preparePatch(r, p);
        assertEquals(plan(r), plan(p));
        assertEquals(r.path("customerQuote").path("rows"), p.path("customerQuote").path("rows"));
        assertFalse(QuotationFinanceReview.pricesChanged(r, p));
        QuotationConfirmation.prepare(r, p); assertFalse(p.has("quoteConfirmed"));
        assertEquals(original, r);
        var fresh = record();
        ((ObjectNode) fresh.path("customerQuote")).putArray("hiddenOptionIds").add("a").add("b").add("c");
        CustomerQuotePrices.initialize(fresh);
        assertEquals(fresh.path("customerQuote"), fresh.path("sheetQuote"));
        assertEquals(3, fresh.path("quoteOptions").size());
        assertEquals(plan(r), plan(fresh));
    }
    @Test void rejectsBadWeightsForgedTotalsDuplicateMissingAndMixedScopeSources() {
        List<Consumer<ObjectNode>> mutations = List.of(
            r -> member(r).put("weight", 49), r -> member(r).put("weight", 0), r -> member(r).put("weight", 50.001),
            r -> ((ArrayNode) member(r).path("sourcePrices")).set(0, DoubleNode.valueOf(0)),
            r -> ((ArrayNode) plan(r).path("systemPrices")).set(0, DoubleNode.valueOf(0)),
            r -> member(r).put("optionId", "b"), r -> member(r).put("optionId", "foreign"),
            r -> ((ObjectNode) r.path("quoteOptions").get(0)).putNull("quote2Usd"),
            r -> ((ObjectNode) r.path("quoteOptions").get(0)).put("available", false),
            r -> ((ObjectNode) r.path("quoteOptions").get(0)).put("countryCode", "GB"),
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
    ObjectNode australia(int... zones) {
        var r = record();
        var options = ((ArrayNode) r.path("quoteOptions")).removeAll();
        var rows = ((ArrayNode) r.path("customerQuote").path("rows")).removeAll();
        var p = plan(r).put("mode", "equal");
        var members = ((ArrayNode) p.path("members")).removeAll();
        int sum = 0;
        for (int zone : zones) {
            String id = "zone-" + zone; sum += zone;
            options.addObject().put("id", id).put("country", "澳大利亚").put("countryCode", "AU")
                .put("quoteRegion", "澳大利亚" + zone + "区").put("carrier", "同一物流商").put("channel", "同一渠道")
                .put("quote1Usd", zone * 10).put("quote2Usd", zone * 20).put("quote3Usd", zone * 30);
            rows.addObject().put("optionId", id).putArray("prices").add(zone * 10).add(zone * 20).add(zone * 30);
            members.addObject().put("optionId", id).put("weight", 1).putArray("sourcePrices").add(zone * 10).add(zone * 20).add(zone * 30);
        }
        var system = p.putArray("systemPrices");
        for (int quantity = 1; quantity <= 3; quantity++)
            system.add(java.math.BigDecimal.valueOf(sum * quantity * 10).divide(java.math.BigDecimal.valueOf(zones.length), 2, java.math.RoundingMode.HALF_UP).doubleValue());
        p.set("prices", system.deepCopy());
        return r;
    }
    @Test void savesAndRestoresAnySelectedAustraliaZonesWithoutChangingSourceSnapshots() {
        for (int mask = 1; mask < 16; mask++) {
            final int selected = mask;
            int[] zones = java.util.stream.IntStream.rangeClosed(1, 4).filter(zone -> (selected & (1 << (zone - 1))) != 0).toArray();
            if (zones.length < 2) continue;
            var r = australia(zones); var sources = r.path("quoteOptions").deepCopy();
            CustomerQuotePrices.initialize(r);
            assertEquals(sources, r.path("quoteOptions"));
            assertEquals(zones.length, plan(r).path("members").size());
            if (mask == 9 || mask == 15) assertEquals("[25.0,50.0,75.0]", plan(r).path("systemPrices").toString());
            var restored = (ObjectNode) mapper.readTree(r.toString());
            var p = patch(restored); CustomerQuotePrices.preparePatch(restored, p);
            assertFalse(QuotationFinanceReview.pricesChanged(restored, p));
        }
    }
    @Test void allowsAllRegionsAndTaxMetadataWithinOneCountry() {
        var valid = australia(2, 3);
        ((ObjectNode) valid.path("quoteOptions").get(0)).put("quoteRegion", "二区").remove("countryCode");
        ((ObjectNode) valid.path("quoteOptions").get(1)).put("quoteRegion", "澳大利亚（三 区）");
        assertDoesNotThrow(() -> CustomerQuotePrices.initialize(valid));
        List<Consumer<ObjectNode>> changes = List.of(
            o -> o.put("quoteRegion", "5区"),
            o -> o.put("quoteRegion", "全国统一"), o -> o.put("quoteRegion", ""), o -> o.put("taxIncluded", true));
        for (var change : changes) {
            var r = australia(2, 3); change.accept((ObjectNode) r.path("quoteOptions").get(0));
            assertDoesNotThrow(() -> CustomerQuotePrices.initialize(r));
        }
        var canada = australia(2, 3);
        for (var option : canada.path("quoteOptions")) ((ObjectNode) option).put("country", "加拿大").put("countryCode", "CA");
        assertDoesNotThrow(() -> CustomerQuotePrices.initialize(canada));
    }

}
