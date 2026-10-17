package com.milano.quotation.purchase;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** Removes the live catalog entry, never the quotation/draft/import snapshots. */
@RestController
@RequestMapping("/api/v1/purchase-products")
public class PurchaseProductForceDeletion {
    private final PurchaseProductRepository products;
    private final PurchaseProductImageRepository images;
    private final PurchaseProductService service;
    private final PurchaseProductDeletionGuard guard;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public PurchaseProductForceDeletion(PurchaseProductRepository products, PurchaseProductImageRepository images,
            PurchaseProductService service, PurchaseProductDeletionGuard guard, AuditService audit,
            JdbcClient jdbc, ObjectMapper mapper) {
        this.products=products; this.images=images; this.service=service; this.guard=guard;
        this.audit=audit; this.jdbc=jdbc; this.mapper=mapper;
    }

    public record Input(Long expectedVersion, String confirmationSku) {}

    @PostMapping("/{sku}/force-delete")
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('PERM_purchase')")
    @Transactional
    public ApiResponse<Void> delete(@PathVariable String sku, @RequestBody Input input) {
        var normalized=sku.trim().toUpperCase(Locale.ROOT);
        if(input.expectedVersion()==null || input.expectedVersion()<0 || input.confirmationSku()==null
                || !normalized.equals(input.confirmationSku().trim().toUpperCase(Locale.ROOT)))
            throw AppException.unprocessable("请输入完整 SKU 确认强制删除，并重新检查商品版本");
        var row=products.findLockedBySku(normalized).orElseThrow(()->AppException.notFound("商品不存在或已被删除"));
        if(row.version!=input.expectedVersion()) throw AppException.conflict("商品已被其他用户修改，请重新打开删除窗口检查后再确认");
        // An in-flight import may still be publishing this SKU. Completed import evidence is retained.
        var active=jdbc.sql("""
            SELECT count(*) FROM purchase_import_row r JOIN import_job j ON j.id=r.job_id
             WHERE (r.applied_product_id=:id OR upper(r.sku)=:sku)
               AND j.status IN ('queued','parsing','import-queued','importing','rollback-queued','rolling-back','processing')
            """).param("id",row.id).param("sku",row.sku).query(Long.class).single();
        if(active>0) throw AppException.conflict("该 SKU 正在导入或回滚，请等待任务结束后再删除");
        var check=guard.inspect(row.id,row.sku,row.version);
        var detail=new LinkedHashMap<String,Object>(check.auditDetail());
        detail.put("productId",row.id.toString());
        detail.put("productSnapshot",service.view(row));
        detail.put("createdAt",row.createdAt.toString());
        detail.put("updatedAt",row.updatedAt.toString());
        detail.put("retainedAssetIds",images.findByProductId(row.id).stream().map(i->i.assetId.toString()).distinct().toList());
        // Audit and deletion commit together. The audit also pins archived images against cleanup.
        audit.record("purchase.force-delete","purchase-product",row.sku,"success",detail);
        products.delete(row);
        products.flush();
        return ApiResponse.ok(null);
    }

    public record DisplaySnapshot(String sku, String category, String productImage, String physicalImage,
                                  String createdAt, String deletedAt) {}

    /** Minimal fallback for old quotation rows without an embedded image; not usable for new quotes. */
    @GetMapping("/deleted-snapshots")
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional(readOnly=true)
    public ApiResponse<List<DisplaySnapshot>> deletedSnapshots(@RequestParam List<String> skus) {
        if(skus.isEmpty() || skus.size()>100) throw AppException.unprocessable("一次最多查询100个 SKU");
        var normalized=skus.stream().map(s->s.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        var rows=jdbc.sql("""
            SELECT resource_id,detail->'productSnapshot' AS snapshot,detail->>'createdAt' AS product_created_at,created_at
              FROM audit_log WHERE action='purchase.force-delete' AND resource_type='purchase-product'
               AND outcome='success' AND resource_id IN (:skus)
             ORDER BY resource_id,created_at DESC,id DESC
            """).param("skus",normalized).query((rs,index)->{
                var snapshot=mapper.readTree(rs.getString("snapshot"));
                return new DisplaySnapshot(rs.getString("resource_id"),snapshot.path("category").asText(""),
                        snapshot.path("productImage").asText(""),snapshot.path("physicalImage").asText(""),
                        rs.getString("product_created_at"),rs.getTimestamp("created_at").toInstant().toString());
            }).list();
        return ApiResponse.ok(rows);
    }
}
