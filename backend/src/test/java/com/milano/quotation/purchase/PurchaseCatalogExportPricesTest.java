package com.milano.quotation.purchase;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class PurchaseCatalogExportPricesTest {
    private final JsonMapper mapper = new JsonMapper();
    private List<BigDecimal> tiers(String json) { return PurchaseCatalogExportPrices.tiers(mapper.readTree(json), false); }

    private List<BigDecimal> prices(String... values) { return java.util.Arrays.stream(values).map(value -> new BigDecimal(value).stripTrailingZeros()).toList(); }

    @Test void exportsOnlyScreenshotUnitPrices() {
        assertEquals(prices("30.00", "28.00", "26.00"), tiers("""
            {"minOrderQty":1,"purchasePriceCny":30,"tier2MinQty":200,"tier2PriceCny":28,"tier3MinQty":2000,"tier3PriceCny":26}
            """));
        assertEquals(prices("19.00", "18.50"), tiers("""
            {"minOrderQty":1,"purchasePriceCny":19,"tier2MinQty":50,"tier2PriceCny":18.5}
            """));
        assertEquals(prices("8.30"), tiers("""
            {"minOrderQty":1,"purchasePriceCny":8.3}
            """));
    }

    @Test void fractionalCentPricesMatchTheCatalogNumberToFixedDisplay() {
        assertEquals(prices("0.07", "2.67"), tiers("""
            {"minOrderQty":1,"purchasePriceCny":0.075,"tier2MinQty":10,"tier2PriceCny":2.675}
            """));
        assertEquals(prices("0.07"), PurchaseCatalogExportPrices.tiers(mapper.readTree("""
            {"fobTiers":[{"minQty":1,"maxQty":null,"unitPriceCny":0.075,"unit":"双"}]}
            """), true));
    }

    @Test void usesCurrentFieldsInsteadOfStaleCachedTiersAndKeepsAllFive() {
        assertEquals(prices("5.00", "4.00", "3.00", "2.00", "1.00"), tiers("""
            {"minOrderQty":"1","purchasePriceCny":"5","tier2MinQty":100,"tier2PriceCny":3,
             "tier3MinQty":10,"tier3PriceCny":4,"tier4MinQty":1000,"tier4PriceCny":2,
             "tier5MinQty":10000,"tier5PriceCny":1,"priceTiers":[{"minQty":1,"unitPriceCny":999}]}
            """));
    }

    @Test void omitsMissingAndInvalidPairsButPreservesZeroPrice() {
        assertTrue(tiers("{}").isEmpty());
        assertEquals(prices("0.00"), tiers("""
            {"minOrderQty":1,"purchasePriceCny":null,"tier2MinQty":10,"tier2PriceCny":"bad",
             "tier3MinQty":20,"tier3PriceCny":0,"tier4MinQty":30,"tier4PriceCny":-1,"tier5PriceCny":5}
            """));
    }

    @Test void legacyPreservesSupplierPrecisionAndTaxBasisWithoutRecalculating() {
        assertEquals(prices("1.2345", "1.1234"), tiers("""
            {"dataSource":"legacy_2026","purchasePriceBasis":"tax_included","purchasePriceCny":1.4,
             "sourceQuotedPriceCny":1.2345,"tier2MinQty":100,"tier2PriceCny":1.1234}
            """));
        assertEquals(prices("21.50"), tiers("""
            {"dataSource":"legacy_2026","purchasePriceBasis":"tax_included","purchasePriceCny":21.5}
            """));
        assertEquals(prices("21.50"), tiers("""
            {"dataSource":"legacy_2026","purchasePriceBasis":"quoted","purchasePriceCny":21.5}
            """));
    }

    @Test void fobUsesParsedBoundariesAndUnits() {
        assertEquals(prices("12.50", "11.80"), PurchaseCatalogExportPrices.tiers(mapper.readTree("""
            {"fobTiers":[{"minQty":10,"maxQty":49,"unitPriceCny":12.5,"unit":"双"},
                         {"minQty":50,"maxQty":null,"unitPriceCny":11.8,"unit":"双"}]}
            """), true));
        assertTrue(PurchaseCatalogExportPrices.tiers(mapper.readTree("{}"), true).isEmpty());
    }
}
