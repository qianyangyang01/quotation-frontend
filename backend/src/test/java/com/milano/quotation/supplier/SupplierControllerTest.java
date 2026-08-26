package com.milano.quotation.supplier;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.AppException;
import com.milano.quotation.purchase.PurchaseProduct;
import com.milano.quotation.purchase.PurchaseProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupplierControllerTest {
    private SupplierRepository suppliers;
    private SupplierProductRepository links;
    private PurchaseProductRepository products;
    private AuditService audit;
    private SupplierController controller;

    @BeforeEach void setUp() {
        suppliers = mock(SupplierRepository.class);
        links = mock(SupplierProductRepository.class);
        products = mock(PurchaseProductRepository.class);
        audit = mock(AuditService.class);
        controller = new SupplierController(suppliers, links, products, audit);
    }

    @Test void linksBySkuAndReturnsProductDetails() {
        var supplierId = UUID.randomUUID();
        var product = product("SKU-1", "服装", "ready");
        when(suppliers.existsById(supplierId)).thenReturn(true);
        when(products.findBySku("SKU-1")).thenReturn(Optional.of(product));
        when(links.existsBySupplierIdAndProductId(supplierId, product.id)).thenReturn(false);

        var result = controller.linkProduct(supplierId,
                new SupplierController.ProductLinkInput(null, " sku-1 ", " SUP-001 ")).data();

        assertEquals(product.id, result.productId());
        assertEquals("SKU-1", result.productSku());
        assertEquals("服装", result.productCategory());
        assertEquals("ready", result.catalogState());
        assertEquals("SUP-001", result.supplierSku());
        assertTrue(result.enabled());
        verify(links).saveAndFlush(any(SupplierProduct.class));
        verify(audit).record(eq("supplier.product-link"), eq("supplier"), eq(supplierId.toString()), eq("success"), anyMap());
    }

    @Test void keepsProductIdCompatibilityAndRejectsMismatchedSku() {
        var supplierId = UUID.randomUUID();
        var byId = product("SKU-1", "服装", "ready");
        var bySku = product("SKU-2", "服装", "ready");
        when(suppliers.existsById(supplierId)).thenReturn(true);
        when(products.findById(byId.id)).thenReturn(Optional.of(byId));
        when(products.findBySku("SKU-2")).thenReturn(Optional.of(bySku));

        var error = assertThrows(AppException.class, () -> controller.linkProduct(supplierId,
                new SupplierController.ProductLinkInput(byId.id, "SKU-2", "")));

        assertEquals("VALIDATION_ERROR", error.code());
        verify(audit).recordIndependent(eq("supplier.product-link"), eq("supplier"), eq(supplierId.toString()), eq("failure"), anyMap());
    }

    @Test void listsUpdatesAndUnlinksSupplierProducts() {
        var supplierId = UUID.randomUUID();
        var product = product("SKU-1", "服装", "disabled");
        var link = link(supplierId, product.id, false);
        when(suppliers.existsById(supplierId)).thenReturn(true);
        when(links.findBySupplierIdOrderByUpdatedAtDesc(supplierId)).thenReturn(List.of(link));
        when(products.findAllById(any())).thenReturn(List.of(product));

        var listed = controller.productLinks(supplierId).data();
        assertEquals(1, listed.size());
        assertEquals("SKU-1", listed.get(0).productSku());

        when(links.findBySupplierIdAndId(supplierId, link.id)).thenReturn(Optional.of(link));
        when(products.findById(product.id)).thenReturn(Optional.of(product));
        var updated = controller.updateProductLink(supplierId, link.id,
                new SupplierController.ProductLinkUpdate("NEW-SUP-SKU", true)).data();
        assertTrue(updated.enabled());
        assertEquals("NEW-SUP-SKU", updated.supplierSku());

        assertNull(controller.unlinkProduct(supplierId, link.id).data());
        verify(links).delete(link);
        verify(audit).record(eq("supplier.product-link-update"), eq("supplier"), eq(supplierId.toString()), eq("success"), anyMap());
        verify(audit).record(eq("supplier.product-unlink"), eq("supplier"), eq(supplierId.toString()), eq("success"), anyMap());
    }

    private static PurchaseProduct product(String sku, String category, String state) {
        var product = mock(PurchaseProduct.class);
        product.id = UUID.randomUUID(); product.sku = sku; product.catalogState = state;
        product.payload = JsonNodeFactory.instance.objectNode().put("category", category);
        return product;
    }

    private static SupplierProduct link(UUID supplierId, UUID productId, boolean enabled) {
        var link = new SupplierProduct(); link.id = UUID.randomUUID(); link.supplierId = supplierId;
        link.productId = productId; link.supplierSku = "SUP-SKU"; link.enabled = enabled;
        link.createdAt = Instant.now(); link.updatedAt = Instant.now(); return link;
    }
}
