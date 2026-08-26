package com.milano.quotation.supplier;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SupplierRepository extends JpaRepository<Supplier, UUID> {
    boolean existsByCodeIgnoreCase(String code);
    Page<Supplier> findByNameContainingIgnoreCaseOrCodeContainingIgnoreCase(String name, String code, Pageable pageable);
}

interface SupplierProductRepository extends JpaRepository<SupplierProduct, UUID> {
    boolean existsBySupplierId(UUID supplierId);
    boolean existsBySupplierIdAndProductId(UUID supplierId, UUID productId);
    java.util.Optional<SupplierProduct> findBySupplierIdAndId(UUID supplierId, UUID id);
    List<SupplierProduct> findBySupplierIdOrderByUpdatedAtDesc(UUID supplierId);
}
