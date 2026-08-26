package com.milano.quotation.supplier;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.*;
import com.milano.quotation.purchase.PurchaseProductRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/suppliers")
@PreAuthorize("hasAuthority('PERM_purchase')")
public class SupplierController {
    private final SupplierRepository suppliers;
    private final SupplierProductRepository links;
    private final PurchaseProductRepository products;
    private final AuditService audit;

    public SupplierController(SupplierRepository suppliers, SupplierProductRepository links,
                              PurchaseProductRepository products, AuditService audit) {
        this.suppliers = suppliers; this.links = links; this.products = products; this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    ApiResponse<PageResponse<View>> list(@RequestParam(defaultValue = "") String query,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        var q = query.trim();
        var pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        return ApiResponse.ok(PageResponse.from(suppliers.findByNameContainingIgnoreCaseOrCodeContainingIgnoreCase(q, q, pageable).map(View::of)));
    }

    @PostMapping
    @Transactional
    ApiResponse<View> create(@Valid @RequestBody Input input) {
        var code = normalize(input.code());
        if (suppliers.existsByCodeIgnoreCase(code)) throw AppException.conflict("供应商编码已存在");
        var now = Instant.now(); var row = new Supplier(); row.id = UUID.randomUUID(); row.createdAt = now; row.code = code;
        apply(row, input); row.updatedAt = now; suppliers.saveAndFlush(row);
        audit.record("supplier.create", "supplier", row.id.toString(), "success", Map.of("code", code));
        return ApiResponse.ok(View.of(row));
    }

    @PutMapping("/{id}")
    @Transactional
    ApiResponse<View> update(@PathVariable UUID id, @RequestHeader("If-Match") long expectedVersion,
                             @Valid @RequestBody Input input) {
        var row = suppliers.findById(id).orElseThrow(() -> AppException.notFound("供应商不存在"));
        if (row.version != expectedVersion) throw AppException.conflict("供应商资料已变化，请刷新后重试");
        var code = normalize(input.code());
        if (!row.code.equalsIgnoreCase(code) && suppliers.existsByCodeIgnoreCase(code)) throw AppException.conflict("供应商编码已存在");
        row.code = code; apply(row, input); row.updatedAt = Instant.now(); suppliers.saveAndFlush(row);
        audit.record("supplier.update", "supplier", id.toString(), "success", Map.of("code", code));
        return ApiResponse.ok(View.of(row));
    }

    @DeleteMapping("/{id}")
    @Transactional
    ApiResponse<Void> delete(@PathVariable UUID id) {
        var row = suppliers.findById(id).orElseThrow(() -> AppException.notFound("供应商不存在"));
        if (links.existsBySupplierId(id)) throw AppException.conflict("供应商已有商品关联，请停用而不是删除");
        suppliers.delete(row); audit.record("supplier.delete", "supplier", id.toString(), "success", Map.of());
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/products")
    @Transactional(readOnly = true)
    ApiResponse<List<ProductLinkView>> productLinks(@PathVariable UUID id) {
        if (!suppliers.existsById(id)) throw AppException.notFound("供应商不存在");
        var rows = links.findBySupplierIdOrderByUpdatedAtDesc(id);
        var productMap = products.findAllById(rows.stream().map(row -> row.productId).distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(product -> product.id, product -> product));
        return ApiResponse.ok(rows.stream().map(row -> ProductLinkView.of(row, productMap.get(row.productId))).toList());
    }

    @PostMapping("/{id}/products")
    @Transactional
    ApiResponse<ProductLinkView> linkProduct(@PathVariable UUID id, @Valid @RequestBody ProductLinkInput input) {
        try {
            if (!suppliers.existsById(id)) throw AppException.notFound("供应商不存在");
            var product = resolveProduct(input);
            if (links.existsBySupplierIdAndProductId(id, product.id)) throw AppException.conflict("该商品已经关联此供应商，请编辑或重新启用现有关联");
            var now = Instant.now(); var link = new SupplierProduct(); link.id = UUID.randomUUID(); link.supplierId = id;
            link.productId = product.id; link.supplierSku = trim(input.supplierSku()); link.enabled = true;
            link.createdAt = now; link.updatedAt = now; links.saveAndFlush(link);
            audit.record("supplier.product-link", "supplier", id.toString(), "success", Map.of("linkId", link.id, "productId", product.id, "sku", product.sku));
            return ApiResponse.ok(ProductLinkView.of(link, product));
        } catch (AppException error) {
            audit.recordIndependent("supplier.product-link", "supplier", id.toString(), "failure", Map.of("sku", trim(input.sku()), "reason", error.getMessage()));
            throw error;
        }
    }

    @PatchMapping("/{id}/products/{linkId}")
    @Transactional
    ApiResponse<ProductLinkView> updateProductLink(@PathVariable UUID id, @PathVariable UUID linkId,
                                                    @Valid @RequestBody ProductLinkUpdate input) {
        try {
            if (!suppliers.existsById(id)) throw AppException.notFound("供应商不存在");
            var link = links.findBySupplierIdAndId(id, linkId).orElseThrow(() -> AppException.notFound("供应商商品关联不存在"));
            link.supplierSku = trim(input.supplierSku()); link.enabled = input.enabled(); link.updatedAt = Instant.now();
            links.saveAndFlush(link);
            var product = products.findById(link.productId).orElseThrow(() -> AppException.notFound("采购商品不存在"));
            audit.record("supplier.product-link-update", "supplier", id.toString(), "success", Map.of("linkId", link.id, "productId", link.productId, "enabled", link.enabled));
            return ApiResponse.ok(ProductLinkView.of(link, product));
        } catch (AppException error) {
            audit.recordIndependent("supplier.product-link-update", "supplier", id.toString(), "failure", Map.of("linkId", linkId, "enabled", input.enabled(), "reason", error.getMessage()));
            throw error;
        }
    }

    @DeleteMapping("/{id}/products/{linkId}")
    @Transactional
    ApiResponse<Void> unlinkProduct(@PathVariable UUID id, @PathVariable UUID linkId) {
        try {
            if (!suppliers.existsById(id)) throw AppException.notFound("供应商不存在");
            var link = links.findBySupplierIdAndId(id, linkId).orElseThrow(() -> AppException.notFound("供应商商品关联不存在"));
            links.delete(link); links.flush();
            audit.record("supplier.product-unlink", "supplier", id.toString(), "success", Map.of("linkId", linkId, "productId", link.productId));
            return ApiResponse.ok(null);
        } catch (AppException error) {
            audit.recordIndependent("supplier.product-unlink", "supplier", id.toString(), "failure", Map.of("linkId", linkId, "reason", error.getMessage()));
            throw error;
        }
    }

    private com.milano.quotation.purchase.PurchaseProduct resolveProduct(ProductLinkInput input) {
        var byId = input.productId() == null ? Optional.<com.milano.quotation.purchase.PurchaseProduct>empty() : products.findById(input.productId());
        var normalizedSku = normalizeSku(input.sku());
        var bySku = normalizedSku.isEmpty() ? Optional.<com.milano.quotation.purchase.PurchaseProduct>empty() : products.findBySku(normalizedSku);
        if (input.productId() == null && normalizedSku.isEmpty()) throw AppException.unprocessable("请选择需要关联的采购商品");
        if (input.productId() != null && byId.isEmpty()) throw AppException.notFound("采购商品不存在");
        if (!normalizedSku.isEmpty() && bySku.isEmpty()) throw AppException.notFound("采购商品不存在");
        if (byId.isPresent() && bySku.isPresent() && !byId.get().id.equals(bySku.get().id)) throw AppException.unprocessable("productId 与 SKU 指向的商品不一致");
        return byId.or(() -> bySku).orElseThrow(() -> AppException.notFound("采购商品不存在"));
    }

    private static void apply(Supplier row, Input input) {
        row.name = input.name().trim(); row.contactName = trim(input.contactName()); row.phone = trim(input.phone());
        row.platform = trim(input.platform()); row.category = trim(input.category()); row.settlementTerms = trim(input.settlementTerms());
        row.leadTimeDays = input.leadTimeDays(); row.rating = input.rating(); row.enabled = input.enabled();
    }
    private static String normalize(String value) { return value.trim().toUpperCase(Locale.ROOT); }
    private static String normalizeSku(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""); }
    private static String trim(String value) { return value == null ? "" : value.trim(); }

    record Input(@NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 160) String name,
                 @Size(max = 80) String contactName, @Size(max = 40) String phone,
                 @Size(max = 120) String platform, @Size(max = 160) String category,
                 @Size(max = 160) String settlementTerms, @Min(0) Integer leadTimeDays,
                 @DecimalMin("0") @DecimalMax("5") BigDecimal rating, boolean enabled) {}
    record ProductLinkInput(UUID productId, @Size(max = 96) String sku, @Size(max = 96) String supplierSku) {}
    record ProductLinkUpdate(@Size(max = 96) String supplierSku, @NotNull Boolean enabled) {}
    record View(UUID id, String code, String name, String contactName, String phone, String platform,
                String category, String settlementTerms, Integer leadTimeDays, BigDecimal rating,
                boolean enabled, long version, Instant createdAt, Instant updatedAt) {
        static View of(Supplier row) { return new View(row.id, row.code, row.name, row.contactName, row.phone,
                row.platform, row.category, row.settlementTerms, row.leadTimeDays, row.rating,
                row.enabled, row.version, row.createdAt, row.updatedAt); }
    }
    record ProductLinkView(UUID id, UUID supplierId, UUID productId, String productSku, String productCategory,
                           String catalogState, String supplierSku, boolean enabled,
                           Instant createdAt, Instant updatedAt) {
        static ProductLinkView of(SupplierProduct row, com.milano.quotation.purchase.PurchaseProduct product) {
            var sku = product == null ? "" : product.sku;
            var category = product == null ? "" : product.payload.path("category").asText("");
            var state = product == null ? "" : product.catalogState;
            return new ProductLinkView(row.id, row.supplierId, row.productId, sku, category, state,
                    row.supplierSku, row.enabled, row.createdAt, row.updatedAt);
        }
    }
}
