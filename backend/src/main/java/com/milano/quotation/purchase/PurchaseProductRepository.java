package com.milano.quotation.purchase;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseProductRepository extends JpaRepository<PurchaseProduct, UUID> {
    Optional<PurchaseProduct> findBySku(String sku);
    List<PurchaseProduct> findAllByOrderByUpdatedAtDesc();
    void deleteBySku(String sku);
}
