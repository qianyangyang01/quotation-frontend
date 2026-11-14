package com.milano.quotation.quote;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.node.*;
import java.math.BigDecimal;

class FobPricingTest {
    @Test void bothRebateClassesRequireExplicitSpecialInvoiceAndIncludedPrices(){
        for(var kind:java.util.List.of("rebate-fob","rebate-cfr-cif")){
            for(var invoice:java.util.List.of("","普票","无票","未确认","非专票","专票待核实"))
                assertThrows(RuntimeException.class,()->FobPricing.invoice(FobQuotation.object().put("invoiceType",invoice).put("purchasePriceBasis","tax_included"),kind));
            assertDoesNotThrow(()->FobPricing.invoice(FobQuotation.object().put("invoiceType","专票13").put("purchasePriceBasis","quoted"),kind));
            assertDoesNotThrow(()->FobPricing.invoice(FobQuotation.object().put("invoiceType","专票").put("purchasePriceBasis","tax_included"),kind));
        }
        assertDoesNotThrow(()->FobPricing.invoice(FobQuotation.object(),"ordinary-fob"));
    }
    static ObjectNode product(String kind){
        var p=FobQuotation.object().put("sku","SKU001").put("classification",kind);
        var parsed=p.putObject("parsed").put("minOrderQty",1).put("orderMultiple",1);
        parsed.putObject("freight").put("unitFreightCny",new BigDecimal("0.6"));
        parsed.putArray("priceTiers").addObject().put("minQty",1).putNull("maxQty").put("unit","件").put("unitPriceCny",22);
        return p;
    }
    static ObjectNode config(){var c=FobQuotation.object().put("version",3);c.set("value",FobPricing.defaults());return c;}
    @Test void classificationPricesUseIncludedCostWithoutAddingTaxAgain(){
        var expected=java.util.Map.of("rebate-fob","3.39","rebate-cfr-cif","3.48","ordinary-fob","3.77");
        expected.forEach((kind,price)->{
            var result=FobPricing.pricing(product(kind),new BigDecimal("7.2"),config());
            assertEquals(price,result.at("/rows/0/declaredUsd").asText());
            assertEquals(new BigDecimal("22.6"),result.at("/rows/0/costCny").decimalValue());
            assertEquals(9,result.at("/rows/0/thresholdQty").asInt());
            assertEquals(3,result.path("version").asLong());
        });
    }
    @Test void coefficientChangesAffectNewCalculationWithoutChangingOldSnapshot(){
        var original=FobPricing.pricing(product("rebate-fob"),new BigDecimal("7.2"),config());
        var changed=config();((ObjectNode)changed.path("value")).put("rebate-fob",new BigDecimal("1.20"));
        assertEquals("3.77",FobPricing.pricing(product("rebate-fob"),new BigDecimal("7.2"),changed).at("/rows/0/declaredUsd").asText());
        assertEquals("3.39",original.at("/rows/0/declaredUsd").asText());
    }
    @Test void settingsRejectMissingInvalidOrUnexpectedValues(){
        assertDoesNotThrow(()->FobPricing.validate(FobPricing.defaults()));
        for(var value:java.util.List.of("-1","0","10.1","1.1234567")){
            var c=FobPricing.defaults();c.put("rebate-fob",new BigDecimal(value));assertThrows(RuntimeException.class,()->FobPricing.validate(c));
        }
        var c=FobPricing.defaults();c.remove("ordinary-fob");assertThrows(RuntimeException.class,()->FobPricing.validate(c));
    }
    @Test void allNestedSerializedCoefficientCopiesAreRemoved(){
        var out=FobQuotation.object();out.set("fob-pricing",FobPricing.defaults());
        out.putArray("records").addObject().putObject("fob").putObject("product").set("pricing",FobPricing.pricing(product("rebate-fob"),new BigDecimal("7.2"),config()));
        out.putArray("revisions").addObject().putObject("before").put("fobCoefficient",1.08);
        FobCoefficientVisibility.redact(out);
        assertFalse(out.toString().contains("fobCoefficient"));assertFalse(out.has("fob-pricing"));
        assertEquals("3.39",out.at("/records/0/fob/product/pricing/rows/0/declaredUsd").asText());
    }
}
