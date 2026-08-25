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
    private PurchaseProductDeletionGuard deletionGuard;
    private PurchaseProductService service;
    private final LinkedHashMap<String, PurchaseProduct> rows = new LinkedHashMap<>();

    @BeforeEach
    void setup() {
        products = mock(PurchaseProductRepository.class);
        images = mock(PurchaseProductImageRepository.class);
        storage = mock(AssetStorageService.class);
        deletionGuard = mock(PurchaseProductDeletionGuard.class);
        service = new PurchaseProductService(products, images, storage, deletionGuard);
        when(products.findBySku(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        when(products.findLockedBySku(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        when(products.saveAndFlush(any())).thenAnswer(call -> {
            var row = (PurchaseProduct) call.getArgument(0);
            rows.put(row.sku, row);
            return row;
        });
        doAnswer(call -> { rows.remove(((PurchaseProduct)call.getArgument(0)).sku); return null; }).when(products).delete(any(PurchaseProduct.class));
        when(images.findByProductId(any())).thenReturn(List.of());
        when(deletionGuard.inspect(any(),anyString(),anyLong())).thenAnswer(call -> new PurchaseProductDeletionGuard.DeletionCheck(true,(Long)call.getArgument(2),0,0,0,0,0,0));
    }

    @Test
    void createsNormalizesReadsPagesAndDeletesProduct() {
        var input = JsonNodeFactory.instance.objectNode().put("sku", " ab 12 ").put("name", "真实商品");
        var created = service.upsert(input);
        assertEquals("AB12", created.path("sku").asText());
        assertEquals("真实商品", service.get("ab 12").path("name").asText());
        assertTrue(service.exists("AB12"));
        when(products.search("AB", PageRequest.of(0, 10)))
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
    void reusesTheSameImageLinkAndFlushesBeforeReplacingIt() {
        var product = PurchaseProduct.create("SKU-IMG", JsonNodeFactory.instance.objectNode().put("sku", "SKU-IMG"), "ready", false, null);
        rows.put(product.sku, product);
        var firstAsset = UUID.randomUUID();
        var existing = new PurchaseProductImage();
        existing.id = UUID.randomUUID(); existing.productId = product.id; existing.assetId = firstAsset; existing.imageType = "product";
        when(images.findFirstByProductIdAndImageTypeOrderBySortOrderAsc(product.id, "product")).thenReturn(Optional.of(existing));

        service.linkAsset(product.sku, firstAsset, "product");
        verify(images, never()).deleteByProductIdAndImageType(product.id, "product");
        verify(images, never()).save(any(PurchaseProductImage.class));

        var replacement = UUID.randomUUID();
        service.linkAsset(product.sku, replacement, "product");
        var order = inOrder(images);
        order.verify(images).deleteByProductIdAndImageType(product.id, "product");
        order.verify(images).flush();
        order.verify(images).save(argThat(link -> link.assetId.equals(replacement) && link.imageType.equals("product")));
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

    @Test
    void disablesEnablesAndRejectsStaleCatalogChanges() {
        var payload=JsonNodeFactory.instance.objectNode().put("sku","SAFE-1").put("weightG",100).put("lengthCm",10).put("widthCm",8).put("heightCm",4).put("minOrderQty",1).put("purchasePriceCny",12.5);
        var product=PurchaseProduct.create("SAFE-1",payload,"ready",true,null);product.version=4;rows.put(product.sku,product);
        var disabled=service.changeCatalogState("SAFE-1","disabled",4);
        assertEquals("disabled",disabled.path("catalogState").asText());assertFalse(disabled.path("quoteReady").asBoolean());
        assertThrows(AppException.class,()->service.changeCatalogState("SAFE-1","ready",3));
        var enabled=service.changeCatalogState("SAFE-1","ready",4);
        assertEquals("ready",enabled.path("catalogState").asText());assertTrue(enabled.path("quoteReady").asBoolean());
        assertThrows(AppException.class,()->service.changeCatalogState("SAFE-1","pending_template",4));
    }

    @Test
    void blocksReferencedDeleteAndRetiresOnlyCollectedImages() {
        var product=PurchaseProduct.create("SAFE-2",JsonNodeFactory.instance.objectNode().put("sku","SAFE-2"),"ready",false,null);product.version=2;rows.put(product.sku,product);
        var blocked=new PurchaseProductDeletionGuard.DeletionCheck(false,2,1,0,1,0,0,0);
        when(deletionGuard.inspect(product.id,product.sku,2)).thenReturn(blocked);
        assertThrows(PurchaseProductService.DeletionBlocked.class,()->service.delete(product.sku,2));
        verify(products,never()).delete(product);

        var assetId=UUID.randomUUID();var image=new PurchaseProductImage();image.id=UUID.randomUUID();image.productId=product.id;image.assetId=assetId;image.imageType="product";
        when(deletionGuard.inspect(product.id,product.sku,2)).thenReturn(new PurchaseProductDeletionGuard.DeletionCheck(true,2,1,0,0,0,0,0));
        when(images.findByProductId(product.id)).thenReturn(List.of(image));when(storage.retireUnreferenced(List.of(assetId))).thenReturn(1);
        var result=service.delete(product.sku,2);assertEquals(1,result.retiredImages());assertFalse(rows.containsKey(product.sku));
        verify(storage).retireUnreferenced(List.of(assetId));
    }

    @Test
    void locksStructuredJsonReferencesAndChecksQuoteReadinessInSkuOrder() {
        var ready=PurchaseProduct.create("BIZ-1",JsonNodeFactory.instance.objectNode().put("sku","BIZ-1"),"ready",true,null);
        when(products.findAllLockedBySkuIn(List.of("BIZ-1","BIZ-2"))).thenReturn(List.of(ready));
        assertEquals(List.of("BIZ-2"),service.notQuoteReadyLocked(List.of(" biz-2 ","BIZ-1","BIZ-2")));

        var payload=JsonNodeFactory.instance.objectNode().put("skuSearch","BIZ-2");
        payload.putObject("product").put("sku","BIZ-1");payload.putArray("bundleItems").addObject().put("sku","BIZ-2");
        service.lockStructuredReferences(payload);
        verify(products,atLeastOnce()).findAllLockedBySkuIn(argThat(skus->skus.size()==2&&skus.containsAll(List.of("BIZ-1","BIZ-2"))));
        assertDoesNotThrow(()->service.lockStructuredReferences(JsonNodeFactory.instance.objectNode()));
    }
}
