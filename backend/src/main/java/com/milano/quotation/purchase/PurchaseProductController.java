package com.milano.quotation.purchase;

import tools.jackson.databind.JsonNode;
import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.PageResponse;
import org.springframework.data.domain.PageRequest;
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
    @GetMapping @PreAuthorize("hasAnyAuthority('PERM_purchase','PERM_quote','PERM_allRecords')") ApiResponse<PageResponse<JsonNode>> list(@RequestParam(defaultValue="")String q,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="100")int size) { var safePage=Math.max(0,page);var safeSize=Math.max(1,Math.min(size,500));return ApiResponse.ok(PageResponse.from(products.page(q,PageRequest.of(safePage,safeSize)))); }
    @GetMapping("/stats") @PreAuthorize("hasAnyAuthority('PERM_purchase','PERM_quote','PERM_allRecords')") ApiResponse<PurchaseProductService.Stats> stats(){return ApiResponse.ok(products.stats());}
    @GetMapping("/{sku}") @PreAuthorize("hasAnyAuthority('PERM_purchase','PERM_quote','PERM_allRecords')") ApiResponse<JsonNode> get(@PathVariable String sku){return ApiResponse.ok(products.get(sku));}
    @GetMapping("/{sku}/deletion-check") @PreAuthorize("hasAuthority('PERM_purchase')") ApiResponse<PurchaseProductDeletionGuard.DeletionCheck> deletionCheck(@PathVariable String sku){return ApiResponse.ok(products.deletionCheck(sku));}
    @PutMapping("/{sku}") @PreAuthorize("hasAuthority('PERM_purchase')") ApiResponse<JsonNode> upsert(@PathVariable String sku, @RequestBody JsonNode body) {
        if (!(body instanceof tools.jackson.databind.node.ObjectNode)) throw com.milano.quotation.common.AppException.unprocessable("商品数据必须为对象");
        ((tools.jackson.databind.node.ObjectNode) body).put("sku", sku); var result=products.upsert(body); audit.record("purchase.upsert","purchase-product",sku,"success", Map.of()); return ApiResponse.ok(result);
    }
    @PutMapping("/batch") @PreAuthorize("hasAuthority('PERM_purchase')") ApiResponse<List<JsonNode>> batch(@RequestBody List<JsonNode> body) {
        var result=products.upsertAll(body); audit.record("purchase.batch-upsert","purchase-product","batch","success", Map.of("count",result.size())); return ApiResponse.ok(result);
    }
    @PostMapping("/{sku}/promote") @PreAuthorize("hasAuthority('PERM_purchase')") ApiResponse<JsonNode> promote(@PathVariable String sku,@RequestBody PromoteInput body){
        var result=products.promote(sku,body.targetSku(),body.expectedVersion());audit.record("purchase.promote","purchase-product",sku,"success",Map.of("targetSku",body.targetSku()));return ApiResponse.ok(result);
    }
    @PostMapping("/{sku}/catalog-state") @PreAuthorize("hasAuthority('PERM_purchase')") ApiResponse<JsonNode> catalogState(@PathVariable String sku,@RequestBody CatalogStateInput body){
        try{var result=products.changeCatalogState(sku,body.state(),body.expectedVersion());audit.record("disabled".equals(body.state())?"purchase.disable":"purchase.enable","purchase-product",sku,"success",Map.of("expectedVersion",body.expectedVersion(),"state",body.state()));return ApiResponse.ok(result);}
        catch(com.milano.quotation.common.AppException error){audit.record("purchase.catalog-state","purchase-product",sku,"failure",Map.of("expectedVersion",body.expectedVersion(),"state",String.valueOf(body.state()),"reason",error.getMessage()));throw error;}
    }
    @DeleteMapping("/{sku}") @PreAuthorize("hasAuthority('PERM_purchase')") ApiResponse<Void> delete(@PathVariable String sku,@RequestParam long expectedVersion) {
        try{var result=products.delete(sku,expectedVersion);var detail=new java.util.LinkedHashMap<String,Object>(result.check().auditDetail());detail.put("retiredImages",result.retiredImages());audit.record("purchase.delete","purchase-product",sku,"success",detail);return ApiResponse.ok(null);}
        catch(PurchaseProductService.DeletionBlocked blocked){audit.record("purchase.delete","purchase-product",sku,"failure",blocked.check().auditDetail());throw blocked;}
        catch(com.milano.quotation.common.AppException error){audit.record("purchase.delete","purchase-product",sku,"failure",Map.of("expectedVersion",expectedVersion,"reason",error.getMessage()));throw error;}
    }
    @PostMapping(value="/{sku}/images/{type}",consumes="multipart/form-data") @PreAuthorize("hasAuthority('PERM_purchase')") ApiResponse<JsonNode> image(@PathVariable String sku,@PathVariable String type,@RequestPart("file")MultipartFile file){var result=products.uploadImage(sku,type,file);audit.record("purchase.image-upload","purchase-product",sku,"success",Map.of("type",type));return ApiResponse.ok(result);}
    record PromoteInput(String targetSku,long expectedVersion){}
    record CatalogStateInput(String state,long expectedVersion){}
}
