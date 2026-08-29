package com.milano.quotation.supplierrecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
        String priceLevel,
        Boolean afterSalesAvailable,
        Integer calculatedScore,
        String scoreStatus,
        List<String> missingScoreItems,
        ScoreBreakdown scoreBreakdown,
        String scorePolicyVersion,
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
        var score = SupplierRecordScoring.evaluate(row);
        return new SupplierRecordView(row.id, row.name, empty(row.industryBelt), empty(row.bossName),
                empty(row.contactDetails), SupplierRecordScoring.normalizeInvoiceType(row.invoiceType), row.taxPoint,
                empty(row.qualityGrade),
                empty(row.deliveryTerms), empty(row.capacityOrder), empty(row.stockingStrategy),
                empty(row.alternativeInquiry), empty(row.corporateAccount), empty(row.corporateBank),
                row.businessLicenseAssetId, row.businessLicenseAssetId == null ? "" : "/api/v1/assets/" + row.businessLicenseAssetId,
                row.hotProductRecommendation, row.freeSample,
                empty(row.afterSales), row.cooperationScore, empty(row.priceLevel), row.afterSalesAvailable,
                row.calculatedScore, score.status(), score.missingItems(), ScoreBreakdown.from(score.breakdown()),
                row.scorePolicyVersion, empty(row.rating), row.monthlyPurchaseAmount,
                empty(row.notes), empty(row.suggestion), row.createdBy, row.updatedBy, row.version,
                row.createdAt, row.updatedAt);
    }

    public record ScoreBreakdown(
            Integer quality,
            Integer delivery,
            Integer afterSales,
            Integer hotProduct,
            Integer freeSample,
            Integer priceLevel,
            Integer invoice
    ) {
        static ScoreBreakdown from(SupplierRecordScoring.Breakdown score) {
            return new ScoreBreakdown(score.quality(), score.delivery(), score.afterSales(), score.hotProduct(),
                    score.freeSample(), score.priceLevel(), score.invoice());
        }
    }

    private static String empty(String value) { return value == null ? "" : value; }
}
