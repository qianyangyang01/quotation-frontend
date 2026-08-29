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
    @Column(name = "boss_name", length = 80) String bossName;
    @Column(name = "contact_details", length = 160) String contactDetails;
    @Column(name = "invoice_type", length = 40) String invoiceType;
    @Column(name = "tax_point", precision = 7, scale = 6) BigDecimal taxPoint;
    @Column(name = "quality_grade", length = 40) String qualityGrade;
    @Column(name = "delivery_terms", length = 80) String deliveryTerms;
    @Column(name = "capacity_order", length = 120) String capacityOrder;
    @Column(name = "stocking_strategy", length = 160) String stockingStrategy;
    @Column(name = "alternative_inquiry", length = 500) String alternativeInquiry;
    @Column(name = "corporate_account", length = 500) String corporateAccount;
    @Column(name = "corporate_bank", length = 160) String corporateBank;
    @Column(name = "business_license_asset_id") UUID businessLicenseAssetId;
    @Column(name = "hot_product_recommendation") Boolean hotProductRecommendation;
    @Column(name = "free_sample") Boolean freeSample;
    @Column(name = "after_sales", length = 160) String afterSales;
    @Column(name = "cooperation_score") Integer cooperationScore;
    @Column(name = "price_level", length = 20) String priceLevel;
    @Column(name = "after_sales_available") Boolean afterSalesAvailable;
    @Column(name = "calculated_score") Integer calculatedScore;
    @Column(name = "score_policy_version", length = 40) String scorePolicyVersion;
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
