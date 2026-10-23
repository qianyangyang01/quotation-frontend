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
    @Test void doesNotConvertLegacyOrIncludedPriceSources() {
        var repository = mock(PurchaseProductRepository.class);
        var service = new PurchasePasteService(repository, mock(PurchaseProductService.class));
        for (var original : List.of(full().put("dataSource", "legacy_2026"), full().put("dataSource", "standard").put("purchasePriceBasis", "tax_included"))) {
            when(repository.findAllBySkuIn(any())).thenReturn(List.of(PurchaseProduct.create("QA-FIVE", original, "ready", true, null)));
            assertThrows(AppException.class, () -> service.preview(List.of(JsonNodeFactory.instance.objectNode().put("sku", "QA-FIVE")), true));
        }
    }
    @Test void missingFreightIsDisclosedAndOldEndpointDoesNotMarkSourcesShared() {
        var service = new PurchasePasteService(mock(PurchaseProductRepository.class), mock(PurchaseProductService.class));
        var input = full(); input.remove("freight100Cny");
        var preview = service.preview(List.of(input), true).rows().getFirst();
        assertTrue(preview.notices().stream().anyMatch(n -> n.contains("FOB报价需补齐")));
        assertFalse(service.preview(List.of(input)).rows().getFirst().effective().has("purchaseUsage"));
    }
}
