package com.milano.quotation.quote;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class FobDiscountTest {
    static ObjectNode discountedInput() {
        var in=FobQuotationTest.input(); ((ObjectNode)in.path("fob")).put("discount",new BigDecimal("0.95"));
        ((ObjectNode)in.at("/fob/sheet/rows/0")).putArray("prices").add(1.44).add(1.47);
        ((ObjectNode)in.at("/fob/sheet/rows/1")).putArray("prices").add(1.28).add(1.30);
        return in;
    }
    @Test void savesDiscountedCanonicalSnapshotWithoutChangingCostOrOriginal() {
        var original=FobQuotationTest.calculate(FobQuotationTest.input());var before=original.deepCopy();
        var out=FobQuotationTest.calculate(discountedInput());
        assertEquals(.95,out.at("/fob/discount").asDouble());
        assertEquals("1.28",out.at("/fob/current/declaredUsd").asText());
        assertEquals("1.44",out.at("/fob/ranges/0/declaredUsd").asText());
        assertEquals("1.30",out.at("/fob/ranges/1/undeclaredUsd").asText());
        assertEquals(1.28,out.path("systemQuoteUsd").asDouble());
        assertEquals(original.at("/fob/tiers"),out.at("/fob/tiers"));
        assertEquals(original.path("totalCostCny"),out.path("totalCostCny"));
        assertEquals(before,original);
        assertEquals(1,original.at("/fob/discount").asInt());
        var identity=FobQuotationTest.input();((ObjectNode)identity.path("fob")).put("discount",1);
        assertEquals(original,FobQuotationTest.calculate(identity));
    }
    @Test void rejectsInvalidDiscountAndUndiscountedOrDoubleDiscountedPrices() {
        for(var value: new String[]{"0","-1","1.01","100"}) {
            var in=discountedInput();((ObjectNode)in.path("fob")).put("discount",new BigDecimal(value));
            assertThrows(RuntimeException.class,()->FobQuotationTest.calculate(in));
        }
        var text=discountedInput();((ObjectNode)text.path("fob")).put("discount","0.95");assertThrows(RuntimeException.class,()->FobQuotationTest.calculate(text));
        var nil=discountedInput();((ObjectNode)nil.path("fob")).putNull("discount");assertThrows(RuntimeException.class,()->FobQuotationTest.calculate(nil));
        var stale=FobQuotationTest.input();((ObjectNode)stale.path("fob")).put("discount",.95);assertThrows(RuntimeException.class,()->FobQuotationTest.calculate(stale));
        var twice=discountedInput();((ObjectNode)twice.at("/fob/sheet/rows/0")).putArray("prices").add(1.37).add(1.40);assertThrows(RuntimeException.class,()->FobQuotationTest.calculate(twice));
    }
    @Test void classifiedCanonicalSaveAndRounding() {
        assertEquals("95.00",FobQuotation.discounted("100.00",new BigDecimal(".95")));
        assertEquals("1.43",FobQuotation.discounted("1.50",new BigDecimal(".95")));
        for(var kind:java.util.List.of("rebate-fob","rebate-cfr-cif","ordinary-fob")) {
            var p=FobPricingTest.product(kind);p.set("pricing",FobPricing.pricing(p,new BigDecimal("7.2"),FobPricingTest.config()));
            var input=discountedInput();var f=(ObjectNode)input.path("fob");f.put("quantity",26).put("displayMode","quantity");f.set("product",p);
            var base=p.at("/pricing/rows/0/declaredUsd").asText();var expected=FobQuotation.discounted(base,new BigDecimal(".95"));
            var sheet=(ObjectNode)f.path("sheet");sheet.putArray("quantityLabels").add("Unit Price");sheet.putArray("rows").addObject().put("key","SKU001-0").putArray("prices").add(new BigDecimal(expected));
            var out=FobQuotation.calculate(input,p,new BigDecimal("7.2"));
            assertEquals(expected,out.at("/fob/current/declaredUsd").asText());
        }
    }
    @Test void repeatedQuantitySnapshotsRetainDiscountAndRejectMixedDiscounts() {
        var single=FobQuotationTest.calculate(discountedInput()).path("fob");var batch=(ObjectNode)single.deepCopy();
        var items=batch.putArray("items");items.add(single.deepCopy());var other=(ObjectNode)single.deepCopy();other.put("quantity",30);items.add(other);
        var split=BatchQuotationSubmission.splitFob(batch).get(0);
        assertEquals(.95,split.path("discount").asDouble());assertEquals(2,split.path("quantities").size());
        other.put("discount",1);assertThrows(RuntimeException.class,()->BatchQuotationSubmission.splitFob(batch));
    }
}
