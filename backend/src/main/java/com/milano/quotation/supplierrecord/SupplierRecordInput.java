package com.milano.quotation.supplierrecord;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record SupplierRecordInput(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 160) String industryBelt,
        @Size(max = 80) String contactRole,
        @Size(max = 160) String relationshipNotes,
        @Size(max = 40) String invoiceType,
        @DecimalMin("0") @DecimalMax("1") BigDecimal taxPoint,
        @Size(max = 40) String qualityGrade,
        @Size(max = 80) String deliveryTerms,
        @Size(max = 120) String capacityOrder,
        @Size(max = 160) String stockingStrategy,
        @Size(max = 500) String alternativeInquiry,
        @Size(max = 500) String costSheet,
        Boolean hotProductRecommendation,
        Boolean freeSample,
        @Size(max = 160) String afterSales,
        @Min(0) @Max(100) Integer cooperationScore,
        @Size(max = 20) String rating,
        @DecimalMin("0") BigDecimal monthlyPurchaseAmount,
        @Size(max = 2000) String notes,
        @Size(max = 2000) String suggestion
) {}
