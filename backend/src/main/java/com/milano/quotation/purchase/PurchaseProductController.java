package com.milano.quotation.purchase;

import com.fasterxml.jackson.databind.JsonNode;
import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/purchase-products")
public class PurchaseProductController {
    private final PurchaseProductService products; private final AuditService audit;
    public PurchaseProductController(PurchaseProductService products, AuditService audit) { this.products=products; this.audit=audit; }
    @GetMapping @PreAuthorize("hasAnyAuthority('PERM_purchase','PERM_quote','PERM_allRecords')") ApiResponse<List<JsonNode>> list() { return ApiResponse.ok(products.list()); }
    @PutMapping("/{sku}") @PreAuthorize("hasAuthority('PERM_purchase')") ApiResponse<JsonNode> upsert(@PathVariable String sku, @RequestBody JsonNode body) {
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("sku", sku); var result=products.upsert(body); audit.record("purchase.upsert","purchase-product",sku,"success", Map.of()); return ApiResponse.ok(result);
    }
    @PutMapping("/batch") @PreAuthorize("hasAuthority('PERM_purchase')") ApiResponse<List<JsonNode>> batch(@RequestBody List<JsonNode> body) {
        var result=products.upsertAll(body); audit.record("purchase.batch-upsert","purchase-product","batch","success", Map.of("count",result.size())); return ApiResponse.ok(result);
    }
    @DeleteMapping("/{sku}") @PreAuthorize("hasAuthority('PERM_purchase')") ApiResponse<Void> delete(@PathVariable String sku) {
        products.delete(sku); audit.record("purchase.delete","purchase-product",sku,"success",Map.of()); return ApiResponse.ok(null);
    }
    @PostMapping(value="/{sku}/images/{type}",consumes="multipart/form-data") @PreAuthorize("hasAuthority('PERM_purchase')") ApiResponse<JsonNode> image(@PathVariable String sku,@PathVariable String type,@RequestPart("file")MultipartFile file){var result=products.uploadImage(sku,type,file);audit.record("purchase.image-upload","purchase-product",sku,"success",Map.of("type",type));return ApiResponse.ok(result);}
}
