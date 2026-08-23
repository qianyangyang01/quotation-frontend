package com.milano.quotation.purchase;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetObject;
import com.milano.quotation.storage.AssetStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PurchaseProductServiceTest {
    private PurchaseProductRepository products;
    private PurchaseProductImageRepository images;
    private AssetStorageService storage;
    private PurchaseProductService service;
    private final LinkedHashMap<String, PurchaseProduct> rows = new LinkedHashMap<>();

    @BeforeEach
    void setup() {
        products = mock(PurchaseProductRepository.class);
        images = mock(PurchaseProductImageRepository.class);
        storage = mock(AssetStorageService.class);
        service = new PurchaseProductService(products, images, storage);
        when(products.findBySku(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        when(products.saveAndFlush(any())).thenAnswer(call -> {
            var row = (PurchaseProduct) call.getArgument(0);
            rows.put(row.sku, row);
            return row;
        });
        doAnswer(call -> { rows.remove((String) call.getArgument(0)); return null; }).when(products).deleteBySku(anyString());
    }

    @Test
    void createsNormalizesReadsPagesAndDeletesProduct() {
        var input = JsonNodeFactory.instance.objectNode().put("sku", " ab 12 ").put("name", "真实商品");
        var created = service.upsert(input);
        assertEquals("AB12", created.path("sku").asText());
        assertEquals("真实商品", service.get("ab 12").path("name").asText());
        assertTrue(service.exists("AB12"));
        when(products.findBySkuContainingIgnoreCase("AB", PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(rows.get("AB12"))));
        assertEquals(1, service.page(" AB ", PageRequest.of(0, 10)).getTotalElements());
        service.delete("AB12");
        assertFalse(rows.containsKey("AB12"));
        assertThrows(AppException.class, () -> service.get("AB12"));
        assertThrows(AppException.class, () -> service.delete("AB12"));
    }

    @Test
    void validatesPayloadAndOptimisticVersion() {
        assertThrows(AppException.class, () -> service.upsert(JsonNodeFactory.instance.arrayNode()));
        assertThrows(AppException.class, () -> service.upsert(JsonNodeFactory.instance.objectNode().put("sku", "非法 SKU!")));
        var existing = PurchaseProduct.create("SKU-1", JsonNodeFactory.instance.objectNode().put("sku", "SKU-1").put("name", "旧值"), "ready", false, null);
        existing.version = 3;
        rows.put(existing.sku, existing);
        var stale = JsonNodeFactory.instance.objectNode().put("sku", "SKU-1").put("name", "新值").put("_version", 2);
        assertThrows(AppException.class, () -> service.upsert(stale));
        stale.put("_version", 3);
        assertEquals("新值", service.upsert(stale).path("name").asText());
        var oversized = JsonNodeFactory.instance.objectNode().put("sku", "BIG-1").put("description", "x".repeat(1_000_010));
        assertThrows(AppException.class, () -> service.upsert(oversized));
        assertThrows(AppException.class, () -> service.upsertAll(java.util.Collections.nCopies(5001, JsonNodeFactory.instance.objectNode())));
    }

    @Test
    void externalizesBase64AndLinksKnownAssetUrls() {
        var asset = mock(AssetObject.class);
        asset.id = UUID.randomUUID();
        when(storage.storeImage(any(byte[].class), anyString())).thenReturn(asset);
        var input = JsonNodeFactory.instance.objectNode().put("sku", "IMG-1")
                .put("productImage", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        var result = service.upsert(input);
        assertEquals("/api/v1/assets/" + asset.id, result.path("productImage").asText());
        verify(images).save(any(PurchaseProductImage.class));
        assertThrows(AppException.class, () -> service.upsert(JsonNodeFactory.instance.objectNode().put("sku", "IMG-2").put("productImage", "data:image/png,not-base64")));
        assertDoesNotThrow(() -> service.upsert(JsonNodeFactory.instance.objectNode().put("sku", "IMG-3").put("productImage", "/api/v1/assets/not-a-uuid")));
    }

    @Test
    void uploadsAndLinksImagesWithValidation() throws Exception {
        var product = PurchaseProduct.create("SKU-2", JsonNodeFactory.instance.objectNode().put("sku", "SKU-2"), "ready", false, null);
        rows.put(product.sku, product);
        var asset = mock(AssetObject.class);
        asset.id = UUID.randomUUID();
        when(storage.storeImage(any(byte[].class), anyString())).thenReturn(asset);
        var file = new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2});
        assertEquals("/api/v1/assets/" + asset.id, service.uploadImage("SKU-2", "product", file).path("productImage").asText());
        service.linkAsset("SKU-2", asset.id, "physical");
        assertEquals("/api/v1/assets/" + asset.id, product.payload.path("physicalImage").asText());
        assertThrows(AppException.class, () -> service.uploadImage("SKU-2", "other", file));
        assertThrows(AppException.class, () -> service.uploadImage("MISSING", "product", file));
        assertThrows(AppException.class, () -> service.linkAsset("SKU-2", asset.id, "other"));
        assertThrows(AppException.class, () -> service.linkAsset("MISSING", asset.id, "product"));
    }

    @Test
    void locksTemplateSkuAndOnlyPromotesCompleteBusinessSku() {
        var template = JsonNodeFactory.instance.objectNode().put("sku", "TESTP260001")
                .put("weightG", 100).put("lengthCm", 10).put("widthCm", 8).put("heightCm", 4)
                .put("minOrderQty", 1).put("purchasePriceCny", 12.5);
        var saved = service.upsertImported(template, null, null, "pending_template", "a".repeat(64));
        assertEquals("pending_template", saved.path("catalogState").asText());
        assertFalse(saved.path("quoteReady").asBoolean());
        assertThrows(AppException.class, () -> service.promote("TESTP260001", "TEST260001", saved.path("_version").asLong()));
        var promoted = service.promote("TESTP260001", "BIZ-260001", saved.path("_version").asLong());
        assertEquals("BIZ-260001", promoted.path("sku").asText());
        assertEquals("ready", promoted.path("catalogState").asText());
        assertTrue(promoted.path("quoteReady").asBoolean());
    }
}
