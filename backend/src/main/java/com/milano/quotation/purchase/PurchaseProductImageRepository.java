package com.milano.quotation.purchase;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PurchaseProductImageRepository extends JpaRepository<PurchaseProductImage, UUID> {
    Optional<PurchaseProductImage> findFirstByProductIdAndImageTypeOrderBySortOrderAsc(UUID productId, String imageType);
    void deleteByProductIdAndImageType(UUID productId, String imageType);
}
