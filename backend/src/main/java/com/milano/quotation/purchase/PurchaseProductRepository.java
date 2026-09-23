package com.milano.quotation.purchase;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PurchaseProductRepository extends JpaRepository<PurchaseProduct, UUID> {
    // A single database snapshot without loading image/notes/tier payloads into Hibernate.
    @Query(value="""
        SELECT jsonb_build_object('sku',sku,'category',payload->'category',
          'purchasePriceCny',payload->'purchasePriceCny')::text
        FROM purchase_product ORDER BY sku
        """,nativeQuery=true)
    List<String> analyticsCatalog();
    Optional<PurchaseProduct> findBySku(String sku);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from PurchaseProduct product where product.sku=:sku")
    Optional<PurchaseProduct> findLockedBySku(@Param("sku") String sku);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from PurchaseProduct product where product.sku in :skus order by product.sku")
    List<PurchaseProduct> findAllLockedBySkuIn(@Param("skus") Collection<String> skus);
    List<PurchaseProduct> findAllBySkuIn(Collection<String> skus);
    Page<PurchaseProduct> findBySkuContainingIgnoreCase(String sku, Pageable pageable);
    // Match once for both the exact total and the page. Materialize only IDs, not JSON payloads.
    @Query(value="""
        WITH matches AS MATERIALIZED (
          SELECT id, updated_at FROM purchase_product
          WHERE lower(sku) LIKE ('%' || lower(:query) || '%')
             OR lower(payload::text) LIKE ('%' || lower(:query) || '%')
        ), selected AS (
          SELECT id FROM matches ORDER BY updated_at DESC,id LIMIT :limit OFFSET :offset
        )
        SELECT p.payload::text AS payload,p.sku,p.version,p.catalog_state AS "catalogState",
          p.quote_ready AS "quoteReady",p.updated_at AS "updatedAt",totals.total
        FROM (SELECT count(*) AS total FROM matches) totals
        LEFT JOIN selected ON true LEFT JOIN purchase_product p ON p.id=selected.id
        ORDER BY p.updated_at DESC,p.id
        """,nativeQuery=true)
    List<SearchRow> searchPage(@Param("query") String query,@Param("limit") int limit,@Param("offset") long offset);
    @Query(value="select set_config('plan_cache_mode','force_custom_plan',true)",nativeQuery=true)
    String useCustomSearchPlan();
    interface SearchRow {
        String getPayload(); String getSku(); Long getVersion(); String getCatalogState();
        Boolean getQuoteReady(); java.time.Instant getUpdatedAt(); long getTotal();
    }
    @Query(value="""
        SELECT count(*) AS total,count(*) FILTER(WHERE quote_ready) AS ready,
          count(*) FILTER(WHERE sku LIKE 'AUTO-%' OR payload->>'skuOrigin'='system') AS generated
        FROM purchase_product
        """,nativeQuery=true)
    StatsRow statistics();
    interface StatsRow { long getTotal(); long getReady(); long getGenerated(); }
    long countByQuoteReadyTrue();
    @Query(value="SELECT count(*) FROM purchase_product WHERE sku LIKE 'AUTO-%' OR payload->>'skuOrigin'='system'",nativeQuery=true) long countGeneratedSku();
    void deleteBySku(String sku);
}
