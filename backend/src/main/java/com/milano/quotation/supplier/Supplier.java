package com.milano.quotation.supplier;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "supplier")
class Supplier {
    @Id UUID id;
    @Column(nullable = false, unique = true, length = 64) String code;
    @Column(nullable = false, length = 160) String name;
    @Column(name = "contact_name", length = 80) String contactName;
    @Column(length = 40) String phone;
    @Column(length = 120) String platform;
    @Column(length = 160) String category;
    @Column(name = "settlement_terms", length = 160) String settlementTerms;
    @Column(name = "lead_time_days") Integer leadTimeDays;
    @Column(precision = 3, scale = 2) BigDecimal rating;
    @Column(nullable = false) boolean enabled;
    @Version long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    protected Supplier() {}
}

@Entity
@Table(name = "supplier_product")
class SupplierProduct {
    @Id UUID id;
    @Column(name = "supplier_id", nullable = false) UUID supplierId;
    @Column(name = "product_id", nullable = false) UUID productId;
    @Column(name = "supplier_sku", length = 96) String supplierSku;
    @Column(nullable = false) boolean enabled;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    protected SupplierProduct() {}
}
