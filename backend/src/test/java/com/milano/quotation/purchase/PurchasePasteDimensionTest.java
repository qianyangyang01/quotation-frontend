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

class PurchasePasteDimensionTest {
    private ObjectNode full() {
        return JsonNodeFactory.instance.objectNode().put("sku", "TC2600009-1")
                .put("weightG", 1750).put("minOrderQty", 1).put("purchasePriceCny", 80.75)
                .put("singleFreightCny", 5).put("freight10Cny", 32).put("taxPoint", 0);
    }

    @Test void legacyZeroWidthDoesNotBlockUnrelatedPreviewOrConfirmation() {
        var repository = mock(PurchaseProductRepository.class);
        var products = mock(PurchaseProductService.class);
        var service = new PurchasePasteService(repository, products);
        var original = full().putNull("lengthCm").put("widthCm", 0).putNull("heightCm")
                .put("catalogState", "active").put("dataSource", "legacy_2026")
                .put("sourceQuotedPriceCny", 80.75).put("purchasePriceBasis", "quoted");
        var product = PurchaseProduct.create("TC2600009-1", original, "active", true, null);
        when(repository.findAllBySkuIn(any())).thenReturn(List.of(product));
        when(repository.findAllLockedBySkuIn(any())).thenReturn(List.of(product));
        when(products.savePasted(any())).thenAnswer(call -> call.getArgument(0));
        List<JsonNode> rows = List.of(JsonNodeFactory.instance.objectNode()
                .put("sku", product.sku).put("notes", "更新备注"));
        var preview = service.preview(rows).rows().getFirst();
        assertEquals("update", preview.action());
        assertEquals(List.of("notes"), preview.changes().stream().map(PurchaseHistoryService.Change::field).toList());
        var saved = service.confirm(new PurchasePasteService.Confirmation(rows, List.of(preview.expected())));
        assertEquals("更新备注", saved.updated().getFirst().path("notes").asText());
        assertEquals(original, product.payload);
        assertTrue(saved.updated().getFirst().path("widthCm").isNumber());
        assertEquals(0, saved.updated().getFirst().path("widthCm").asInt());
    }

    @Test void zeroPatchesPreserveExistingDimensionsButStillUpdateZeroPriceFreightAndTax() {
        var repository = mock(PurchaseProductRepository.class);
        var service = new PurchasePasteService(repository, mock(PurchaseProductService.class));
        var original = full().put("lengthCm", 20).put("widthCm", 15).put("heightCm", 35).put("taxPoint", .08);
        when(repository.findAllBySkuIn(any())).thenReturn(List.of(PurchaseProduct.create("TC2600009-1", original, "active", true, null)));
        var preview = service.preview(List.of(JsonNodeFactory.instance.objectNode().put("sku", "TC2600009-1")
                .put("lengthCm", 0).put("widthCm", 0).put("heightCm", 0)
                .put("purchasePriceCny", 0).put("singleFreightCny", 0).put("taxPoint", 0))).rows().getFirst();
        for (var dimension : PurchasePasteValidator.DIMENSIONS)
            assertEquals(original.get(dimension), preview.effective().get(dimension));
        for (var field : List.of("purchasePriceCny", "singleFreightCny", "taxPoint"))
            assertEquals(0, preview.effective().path(field).asDouble());
    }

    @Test void zeroAndBlankDimensionsAllowNewRowsWhilePartialPositiveAndInvalidValuesRemainRejected() {
        var repository = mock(PurchaseProductRepository.class);
        when(repository.findAllBySkuIn(any())).thenReturn(List.of());
        var service = new PurchasePasteService(repository, mock(PurchaseProductService.class));
        for (var row : List.of(full(), full().put("widthCm", 0), full().put("lengthCm", 0).put("widthCm", 0).put("heightCm", 0))) {
            assertDoesNotThrow(() -> PurchasePasteValidator.validateValues(row.deepCopy(), 1, false));
            var created = service.preview(List.of(row)).rows().getFirst();
            assertEquals("create", created.action());
            for (var field : PurchasePasteValidator.DIMENSIONS) assertFalse(created.effective().hasNonNull(field));
        }
        for (var row : List.of(full().put("lengthCm", 20).put("widthCm", 0).put("heightCm", 35),
                full().put("widthCm", -1), full().put("widthCm", "bad"))) {
            assertThrows(AppException.class, () -> service.preview(List.of(row)));
            assertThrows(AppException.class, () -> PurchasePasteValidator.validateValues(row, 1, false));
        }
    }
}
