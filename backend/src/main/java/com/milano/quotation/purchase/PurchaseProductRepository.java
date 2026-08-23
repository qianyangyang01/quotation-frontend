package com.milano.quotation.purchase;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseProductRepository extends JpaRepository<PurchaseProduct, UUID> {
    Optional<PurchaseProduct> findBySku(String sku);
    Page<PurchaseProduct> findBySkuContainingIgnoreCase(String sku, Pageable pageable);
    long countByQuoteReadyTrue();
    void deleteBySku(String sku);
}
