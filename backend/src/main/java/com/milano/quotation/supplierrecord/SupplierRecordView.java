package com.milano.quotation.supplierrecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SupplierRecordView(
        UUID id,
        String name,
        String industryBelt,
        String bossName,
        String contactDetails,
        String invoiceType,
        BigDecimal taxPoint,
        String qualityGrade,
        String deliveryTerms,
        String capacityOrder,
        String stockingStrategy,
        String alternativeInquiry,
        String corporateAccount,
        String corporateBank,
        UUID businessLicenseAssetId,
        String businessLicenseUrl,
        Boolean hotProductRecommendation,
        Boolean freeSample,
        String afterSales,
        Integer cooperationScore,
        String rating,
        BigDecimal monthlyPurchaseAmount,
        String notes,
        String suggestion,
        String createdBy,
        String updatedBy,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    static SupplierRecordView from(SupplierRecord row) {
        return new SupplierRecordView(row.id, row.name, empty(row.industryBelt), empty(row.bossName),
                empty(row.contactDetails), empty(row.invoiceType), row.taxPoint, empty(row.qualityGrade),
                empty(row.deliveryTerms), empty(row.capacityOrder), empty(row.stockingStrategy),
                empty(row.alternativeInquiry), empty(row.corporateAccount), empty(row.corporateBank),
                row.businessLicenseAssetId, row.businessLicenseAssetId == null ? "" : "/api/v1/assets/" + row.businessLicenseAssetId,
                row.hotProductRecommendation, row.freeSample,
                empty(row.afterSales), row.cooperationScore, empty(row.rating), row.monthlyPurchaseAmount,
                empty(row.notes), empty(row.suggestion), row.createdBy, row.updatedBy, row.version,
                row.createdAt, row.updatedAt);
    }

    private static String empty(String value) { return value == null ? "" : value; }
}
