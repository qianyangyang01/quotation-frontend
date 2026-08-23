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
        return ApiResponse.ok(links.findBySupplierIdOrderByUpdatedAtDesc(id).stream().map(ProductLinkView::of).toList());
    }

    @PostMapping("/{id}/products")
    @Transactional
    ApiResponse<ProductLinkView> linkProduct(@PathVariable UUID id, @Valid @RequestBody ProductLinkInput input) {
        if (!suppliers.existsById(id)) throw AppException.notFound("供应商不存在");
        if (!products.existsById(input.productId())) throw AppException.notFound("采购商品不存在");
        if (links.existsBySupplierIdAndProductId(id, input.productId())) throw AppException.conflict("该商品已经关联此供应商");
        var now = Instant.now(); var link = new SupplierProduct(); link.id = UUID.randomUUID(); link.supplierId = id;
        link.productId = input.productId(); link.supplierSku = trim(input.supplierSku()); link.enabled = true;
        link.createdAt = now; link.updatedAt = now; links.save(link);
        audit.record("supplier.product-link", "supplier", id.toString(), "success", Map.of("productId", input.productId()));
        return ApiResponse.ok(ProductLinkView.of(link));
    }

    private static void apply(Supplier row, Input input) {
        row.name = input.name().trim(); row.contactName = trim(input.contactName()); row.phone = trim(input.phone());
        row.platform = trim(input.platform()); row.category = trim(input.category()); row.settlementTerms = trim(input.settlementTerms());
        row.leadTimeDays = input.leadTimeDays(); row.rating = input.rating(); row.enabled = input.enabled();
    }
    private static String normalize(String value) { return value.trim().toUpperCase(Locale.ROOT); }
    private static String trim(String value) { return value == null ? "" : value.trim(); }

    record Input(@NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 160) String name,
                 @Size(max = 80) String contactName, @Size(max = 40) String phone,
                 @Size(max = 120) String platform, @Size(max = 160) String category,
                 @Size(max = 160) String settlementTerms, @Min(0) Integer leadTimeDays,
                 @DecimalMin("0") @DecimalMax("5") BigDecimal rating, boolean enabled) {}
    record ProductLinkInput(@NotNull UUID productId, @Size(max = 96) String supplierSku) {}
    record View(UUID id, String code, String name, String contactName, String phone, String platform,
                String category, String settlementTerms, Integer leadTimeDays, BigDecimal rating,
                boolean enabled, long version, Instant createdAt, Instant updatedAt) {
        static View of(Supplier row) { return new View(row.id, row.code, row.name, row.contactName, row.phone,
                row.platform, row.category, row.settlementTerms, row.leadTimeDays, row.rating,
                row.enabled, row.version, row.createdAt, row.updatedAt); }
    }
    record ProductLinkView(UUID id, UUID supplierId, UUID productId, String supplierSku, boolean enabled,
                           Instant createdAt, Instant updatedAt) {
        static ProductLinkView of(SupplierProduct row) { return new ProductLinkView(row.id, row.supplierId,
                row.productId, row.supplierSku, row.enabled, row.createdAt, row.updatedAt); }
    }
}
