package com.milano.quotation.supplierrecord;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "supplier_record")
class SupplierRecord {
    @Id UUID id;
    @Column(nullable = false, length = 160) String name;
    @Column(name = "industry_belt", length = 160) String industryBelt;
    @Column(name = "contact_role", length = 80) String contactRole;
    @Column(name = "relationship_notes", length = 160) String relationshipNotes;
    @Column(name = "invoice_type", length = 40) String invoiceType;
    @Column(name = "tax_point", precision = 7, scale = 6) BigDecimal taxPoint;
    @Column(name = "quality_grade", length = 40) String qualityGrade;
    @Column(name = "delivery_terms", length = 80) String deliveryTerms;
    @Column(name = "capacity_order", length = 120) String capacityOrder;
    @Column(name = "stocking_strategy", length = 160) String stockingStrategy;
    @Column(name = "alternative_inquiry", length = 500) String alternativeInquiry;
    @Column(name = "cost_sheet", length = 500) String costSheet;
    @Column(name = "hot_product_recommendation") Boolean hotProductRecommendation;
    @Column(name = "free_sample") Boolean freeSample;
    @Column(name = "after_sales", length = 160) String afterSales;
    @Column(name = "cooperation_score") Integer cooperationScore;
    @Column(length = 20) String rating;
    @Column(name = "monthly_purchase_amount", precision = 18, scale = 2) BigDecimal monthlyPurchaseAmount;
    @Column(length = 2000) String notes;
    @Column(length = 2000) String suggestion;
    @Column(name = "created_by", nullable = false, length = 64) String createdBy;
    @Column(name = "updated_by", nullable = false, length = 64) String updatedBy;
    @Version long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;

    protected SupplierRecord() {}
}
