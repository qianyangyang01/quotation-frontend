package com.milano.quotation.purchase;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SharedPurchasePasteTest {
    ObjectNode full() {
        return JsonNodeFactory.instance.objectNode().put("sku", "QA-FIVE").put("weightG", 120)
            .put("minOrderQty", 1).put("purchasePriceCny", 20).put("tier2MinQty", 10).put("tier2PriceCny", 18)
            .put("tier3MinQty", 100).put("tier3PriceCny", 16).put("tier4MinQty", 500).put("tier4PriceCny", 14)
            .put("tier5MinQty", 1000).put("tier5PriceCny", 12).put("singleFreightCny", 5)
            .put("freight10Cny", 20).put("freight100Cny", 100).put("taxPoint", .08);
    }
    @Test void validatesLastTiersAndPreservesZero() {
        assertDoesNotThrow(() -> PurchasePasteValidator.validateValues(full().put("tier5PriceCny", 0), 1, false));
        for (var row : List.of(full().putNull("tier4PriceCny"), full().put("tier5MinQty", 500), full().put("tier4MinQty", 99), full().put("tier5MinQty", 1000.5), full().put("tier5PriceCny", -1)))
            assertThrows(AppException.class, () -> PurchasePasteValidator.validateValues(row, 1, false));
    }
    @Test void savesOneSharedSourceAndRejectsStaleVersionsBeforeWrites() {
        var repository = mock(PurchaseProductRepository.class); var products = mock(PurchaseProductService.class);
        var service = new PurchasePasteService(repository, products);
        var original = full().put("dataSource", "standard").put("catalogState", "ready");
        var product = PurchaseProduct.create("QA-FIVE", original, "ready", true, null);
        when(repository.findAllBySkuIn(any())).thenReturn(List.of(product));
        when(repository.findAllLockedBySkuIn(any())).thenReturn(List.of(product));
        when(products.savePasted(any())).thenAnswer(call -> call.getArgument(0));
        List<JsonNode> patches = List.of(JsonNodeFactory.instance.objectNode().put("sku", "QA-FIVE").put("tier5PriceCny", 0));
        var preview = service.preview(patches, true).rows().getFirst();
        assertEquals("shared", preview.effective().path("purchaseUsage").asText());
        assertEquals(100, preview.effective().path("freight100Cny").asInt());
        assertEquals(List.of("tier5PriceCny", "purchaseUsage"), preview.changes().stream().map(PurchaseHistoryService.Change::field).toList());
        var request = new PurchasePasteService.Confirmation(patches, List.of(preview.expected()));
        product.version++;
        assertThrows(AppException.class, () -> service.confirmShared(request)); verifyNoInteractions(products);
        product.version--;
        var result = service.confirmShared(request);
        assertEquals(1, result.updated().size()); assertEquals(0, result.updated().getFirst().path("tier5PriceCny").asInt());
        verify(products, times(1)).savePasted(any()); assertEquals(original, product.payload);
    }
    @Test void updatesLegacyAndIncludedPriceSourcesWithoutConvertingThem() {
        var repository = mock(PurchaseProductRepository.class);
        var products = mock(PurchaseProductService.class);
        var service = new PurchasePasteService(repository, products);
        when(products.savePasted(any())).thenAnswer(call -> call.getArgument(0));
        for (var original : List.of(full().put("dataSource", "legacy_2026"), full().put("dataSource", "standard").put("purchasePriceBasis", "tax_included"))) {
            var product = PurchaseProduct.create("QA-FIVE", original, "ready", true, null);
            when(repository.findAllBySkuIn(any())).thenReturn(List.of(product));
            when(repository.findAllLockedBySkuIn(any())).thenReturn(List.of(product));
            List<JsonNode> patches = List.of(JsonNodeFactory.instance.objectNode().put("sku", "QA-FIVE").put("notes", "更新资料"));
            var preview = service.preview(patches, true).rows().getFirst();
            assertEquals("update", preview.action());
            assertEquals(original.path("dataSource"), preview.effective().path("dataSource"));
            assertEquals(20, preview.effective().path("purchasePriceCny").asInt());
            assertFalse(preview.effective().has("purchaseUsage"));
            assertTrue(preview.notices().stream().anyMatch(n -> n.contains("不自动切换FOB资料来源")));
            var saved = service.confirmShared(new PurchasePasteService.Confirmation(patches, List.of(preview.expected()))).updated().getFirst();
            assertEquals(preview.effective(), saved);
            assertEquals("更新资料", saved.path("notes").asText());
            assertEquals(original, product.payload, "preview leaves the original source untouched");
        }
    }
    @Test void legacySparsePricesKeepAutomaticTaxAndSinglePieceFreightRules() {
        var repository = mock(PurchaseProductRepository.class);
        var service = new PurchasePasteService(repository, mock(PurchaseProductService.class));
        var original = full().put("dataSource", "legacy_2026").put("legacyPriceMode", "auto_tax").put("catalogState", "ready")
                .put("sourceQuotedPriceCny", 100).put("purchasePriceCny", 110).put("taxIncludedPriceCny", 110)
                .put("purchasePriceBasis", "tax_included").put("taxPoint", .1);
        original.remove(List.of("freight10Cny", "freight100Cny"));
        when(repository.findAllBySkuIn(any())).thenReturn(List.of(PurchaseProduct.create("QA-FIVE", original, "ready", true, null)));
        var preview = service.preview(List.of(JsonNodeFactory.instance.objectNode().put("sku", "QA-FIVE").put("purchasePriceCny", 80)), true).rows().getFirst();
        assertEquals("legacy_2026", preview.effective().path("dataSource").asText());
        assertEquals("auto_tax", preview.effective().path("legacyPriceMode").asText());
        assertEquals("tax_included", preview.effective().path("purchasePriceBasis").asText());
        assertEquals(80, preview.effective().path("sourceQuotedPriceCny").asInt());
        assertEquals(88, preview.effective().path("purchasePriceCny").asInt());
        assertEquals(88, preview.effective().path("taxIncludedPriceCny").asInt());
        assertFalse(preview.effective().has("purchaseUsage"));
        assertFalse(preview.effective().has("freight10Cny"));
        assertTrue(preview.notices().stream().anyMatch(n -> n.contains("含票价 88")));
        var unchanged = service.preview(List.of(JsonNodeFactory.instance.objectNode().put("sku", "QA-FIVE")), true).rows().getFirst();
        assertEquals("unchanged", unchanged.action());
        assertTrue(unchanged.changes().isEmpty());
    }
    @Test void missingFreightIsDisclosedAndOldEndpointDoesNotMarkSourcesShared() {
        var service = new PurchasePasteService(mock(PurchaseProductRepository.class), mock(PurchaseProductService.class));
        var input = full(); input.remove("freight100Cny");
        var preview = service.preview(List.of(input), true).rows().getFirst();
        assertTrue(preview.notices().stream().anyMatch(n -> n.contains("FOB报价需补齐")));
        assertFalse(service.preview(List.of(input)).rows().getFirst().effective().has("purchaseUsage"));
    }
    @Test void legacyFixedIncludedPriceCanBeUpdatedInTheUnifiedEntry() {
        var repository = mock(PurchaseProductRepository.class);
        var service = new PurchasePasteService(repository, mock(PurchaseProductService.class));
        var original = full().put("dataSource", "legacy_2026").put("purchasePriceCny", 125)
                .put("taxIncludedPriceCny", 125).put("purchasePriceBasis", "tax_included").put("catalogState", "ready");
        when(repository.findAllBySkuIn(any())).thenReturn(List.of(PurchaseProduct.create("QA-FIVE", original, "ready", true, null)));
        var preview = service.preview(List.of(JsonNodeFactory.instance.objectNode().put("sku", "QA-FIVE")
                .put("purchasePriceCny", 80).put("taxIncludedPriceCny", 88)), true).rows().getFirst();
        assertEquals("update", preview.action());
        assertEquals(88, preview.effective().path("purchasePriceCny").asInt());
        assertEquals(88, preview.effective().path("taxIncludedPriceCny").asInt());
        assertEquals("legacy_2026", preview.effective().path("dataSource").asText());
        assertFalse(preview.effective().has("purchaseUsage"));
        assertTrue(preview.notices().stream().anyMatch(n -> n.contains("本入口填写“含票价”")));
    }
}
