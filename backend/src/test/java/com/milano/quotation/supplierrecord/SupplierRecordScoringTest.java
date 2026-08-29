package com.milano.quotation.supplierrecord;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SupplierRecordScoringTest {
    @Test
    void appliesDeliveryBoundaries() {
        assertEquals(100, score("0", "专票", "0.11"));
        assertEquals(95, score("1", "专票", "0.11"));
        assertEquals(90, score("2", "专票", "0.11"));
        assertEquals(90, score("7", "专票", "0.11"));
        assertEquals(80, score("8", "专票", "0.11"));
    }

    @Test
    void appliesInvoiceThresholds() {
        assertEquals(100, score("0", "专票", "0.11"));
        assertEquals(85, score("0", "专票", "0.110001"));
        assertEquals(95, score("0", "普票", "0.01"));
        assertEquals(85, score("0", "普票", "0.010001"));
        assertEquals(85, score("0", "没票", null));
        assertEquals(85, score("0", "不开票", "0.12"));
    }

    @Test
    void leavesScorePendingWhenAnyRequiredDimensionIsMissing() {
        var row = complete();
        row.priceLevel = null;
        row.afterSalesAvailable = null;
        var result = SupplierRecordScoring.evaluate(row);

        assertNull(result.total());
        assertNull(result.policyVersion());
        assertEquals("PENDING", result.status());
        assertEquals(java.util.List.of("afterSales", "priceLevel"), result.missingItems());
        assertNull(result.breakdown().afterSales());
        assertNull(result.breakdown().priceLevel());
    }

    @Test
    void acceptsDeliveryValuesBeyondIntegerRangeAsCompleteZeroPointValues() {
        var row = complete();
        row.deliveryTerms = "2147483648";

        var result = SupplierRecordScoring.evaluate(row);

        assertEquals("COMPLETE", result.status());
        assertEquals(0, result.breakdown().delivery());
        assertEquals(80, result.total());
    }

    private static int score(String delivery, String invoiceType, String taxPoint) {
        var row = complete();
        row.deliveryTerms = delivery;
        row.invoiceType = invoiceType;
        row.taxPoint = taxPoint == null ? null : new BigDecimal(taxPoint);
        return SupplierRecordScoring.evaluate(row).total();
    }

    private static SupplierRecord complete() {
        var row = new SupplierRecord();
        row.qualityGrade = "优";
        row.deliveryTerms = "0";
        row.afterSalesAvailable = true;
        row.hotProductRecommendation = true;
        row.freeSample = true;
        row.priceLevel = "市场最低";
        row.invoiceType = "专票";
        row.taxPoint = new BigDecimal("0.11");
        return row;
    }
}
