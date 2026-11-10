package com.milano.quotation.purchase;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PurchaseCatalogExportPricesTest {
    private final JsonMapper mapper = new JsonMapper();
    private List<String> tiers(String json) { return PurchaseCatalogExportPrices.tiers(mapper.readTree(json), false); }

    @Test void exportsScreenshotRangesAndPrices() {
        assertEquals(List.of("1–199件；¥30.00/件", "200–1999件；¥28.00/件", "2000件起；¥26.00/件"), tiers("""
            {"minOrderQty":1,"purchasePriceCny":30,"tier2MinQty":200,"tier2PriceCny":28,"tier3MinQty":2000,"tier3PriceCny":26}
            """));
        assertEquals(List.of("1–49件；¥19.00/件", "50件起；¥18.50/件"), tiers("""
            {"minOrderQty":1,"purchasePriceCny":19,"tier2MinQty":50,"tier2PriceCny":18.5}
            """));
        assertEquals(List.of("1件起；¥8.30/件"), tiers("""
            {"minOrderQty":1,"purchasePriceCny":8.3}
            """));
    }

    @Test void usesCurrentFieldsInsteadOfStaleCachedTiersAndKeepsAllFive() {
        assertEquals(List.of("1–9件；¥5.00/件", "10–99件；¥4.00/件", "100–999件；¥3.00/件", "1000–9999件；¥2.00/件", "10000件起；¥1.00/件"), tiers("""
            {"minOrderQty":"1","purchasePriceCny":"5","tier2MinQty":100,"tier2PriceCny":3,
             "tier3MinQty":10,"tier3PriceCny":4,"tier4MinQty":1000,"tier4PriceCny":2,
             "tier5MinQty":10000,"tier5PriceCny":1,"priceTiers":[{"minQty":1,"unitPriceCny":999}]}
            """));
    }

    @Test void omitsMissingAndInvalidPairsButPreservesZeroPrice() {
        assertTrue(tiers("{}").isEmpty());
        assertEquals(List.of("20件起；¥0.00/件"), tiers("""
            {"minOrderQty":1,"purchasePriceCny":null,"tier2MinQty":10,"tier2PriceCny":"bad",
             "tier3MinQty":20,"tier3PriceCny":0,"tier4MinQty":30,"tier4PriceCny":-1,"tier5PriceCny":5}
            """));
    }

    @Test void legacyPreservesSupplierPrecisionAndTaxBasisWithoutRecalculating() {
        assertEquals(List.of("1–99件；未税单价 ¥1.2345/件", "100件起；未税单价 ¥1.1234/件"), tiers("""
            {"dataSource":"legacy_2026","purchasePriceBasis":"tax_included","purchasePriceCny":1.4,
             "sourceQuotedPriceCny":1.2345,"tier2MinQty":100,"tier2PriceCny":1.1234}
            """));
        assertEquals(List.of("1件起；含票价 ¥21.50/件"), tiers("""
            {"dataSource":"legacy_2026","purchasePriceBasis":"tax_included","purchasePriceCny":21.5}
            """));
        assertEquals(List.of("1件起；未税单价 ¥21.50/件"), tiers("""
            {"dataSource":"legacy_2026","purchasePriceBasis":"quoted","purchasePriceCny":21.5}
            """));
    }

    @Test void fobUsesParsedBoundariesAndUnits() {
        assertEquals(List.of("10–49双；¥12.50/双", "50双起；¥11.80/双"), PurchaseCatalogExportPrices.tiers(mapper.readTree("""
            {"fobTiers":[{"minQty":10,"maxQty":49,"unitPriceCny":12.5,"unit":"双"},
                         {"minQty":50,"maxQty":null,"unitPriceCny":11.8,"unit":"双"}]}
            """), true));
        assertTrue(PurchaseCatalogExportPrices.tiers(mapper.readTree("{}"), true).isEmpty());
    }
}
