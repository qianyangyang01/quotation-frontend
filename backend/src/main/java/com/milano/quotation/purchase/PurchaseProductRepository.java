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
    Optional<PurchaseProduct> findBySku(String sku);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from PurchaseProduct product where product.sku=:sku")
    Optional<PurchaseProduct> findLockedBySku(@Param("sku") String sku);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from PurchaseProduct product where product.sku in :skus order by product.sku")
    List<PurchaseProduct> findAllLockedBySkuIn(@Param("skus") Collection<String> skus);
    List<PurchaseProduct> findAllBySkuIn(Collection<String> skus);
    Page<PurchaseProduct> findBySkuContainingIgnoreCase(String sku, Pageable pageable);
    @Query(value="SELECT * FROM purchase_product WHERE :query='' OR lower(sku) LIKE concat('%',lower(:query),'%') OR lower(payload::text) LIKE concat('%',lower(:query),'%') ORDER BY updated_at DESC",countQuery="SELECT count(*) FROM purchase_product WHERE :query='' OR lower(sku) LIKE concat('%',lower(:query),'%') OR lower(payload::text) LIKE concat('%',lower(:query),'%')",nativeQuery=true) Page<PurchaseProduct> search(@Param("query")String query,Pageable pageable);
    long countByQuoteReadyTrue();
    @Query(value="SELECT count(*) FROM purchase_product WHERE sku LIKE 'AUTO-%' OR payload->>'skuOrigin'='system'",nativeQuery=true) long countGeneratedSku();
    void deleteBySku(String sku);
}
