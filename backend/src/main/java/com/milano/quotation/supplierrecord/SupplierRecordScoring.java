package com.milano.quotation.supplierrecord;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

final class SupplierRecordScoring {
    static final String POLICY_VERSION = "SUPPLIER_SCORE_V1";

    private SupplierRecordScoring() {}

    static Result evaluate(SupplierRecord row) {
        var missing = new ArrayList<String>();

        Integer quality = switch (value(row.qualityGrade)) {
            case "优" -> 30;
            case "良" -> 20;
            case "不良" -> 0;
            default -> missing(missing, "quality");
        };
        Integer delivery = delivery(row.deliveryTerms, missing);
        Integer afterSales = booleanScore(row.afterSalesAvailable, 10, "afterSales", missing);
        Integer hotProduct = booleanScore(row.hotProductRecommendation, 10, "hotProduct", missing);
        Integer freeSample = booleanScore(row.freeSample, 5, "freeSample", missing);
        Integer priceLevel = switch (value(row.priceLevel)) {
            case "市场最低" -> 10;
            case "居中" -> 5;
            case "偏高" -> 0;
            default -> missing(missing, "priceLevel");
        };
        Integer invoice = invoice(row.invoiceType, row.taxPoint, missing);

        var breakdown = new Breakdown(quality, delivery, afterSales, hotProduct, freeSample, priceLevel, invoice);
        if (!missing.isEmpty()) return new Result(null, "PENDING", List.copyOf(missing), breakdown, null);
        int total = quality + delivery + afterSales + hotProduct + freeSample + priceLevel + invoice;
        return new Result(total, "COMPLETE", List.of(), breakdown, POLICY_VERSION);
    }

    static String normalizeInvoiceType(String invoiceType) {
        return "不开票".equals(value(invoiceType)) ? "没票" : value(invoiceType);
    }

    private static Integer delivery(String raw, List<String> missing) {
        var value = value(raw);
        if (!value.matches("(?:0|[1-9]\\d*)")) return missing(missing, "delivery");
        try {
            var days = new BigInteger(value);
            if (days.signum() == 0) return 20;
            if (days.equals(BigInteger.ONE)) return 15;
            if (days.compareTo(BigInteger.valueOf(7)) <= 0) return 10;
            return 0;
        } catch (NumberFormatException ignored) {
            return missing(missing, "delivery");
        }
    }

    private static Integer invoice(String rawType, BigDecimal taxPoint, List<String> missing) {
        return switch (normalizeInvoiceType(rawType)) {
            case "专票" -> taxPoint == null
                    ? missing(missing, "invoice")
                    : taxPoint.compareTo(new BigDecimal("0.11")) <= 0 ? 15 : 0;
            case "普票" -> taxPoint == null
                    ? missing(missing, "invoice")
                    : taxPoint.compareTo(new BigDecimal("0.01")) <= 0 ? 10 : 0;
            case "没票" -> 0;
            default -> missing(missing, "invoice");
        };
    }

    private static Integer booleanScore(Boolean value, int yesScore, String key, List<String> missing) {
        if (value == null) return missing(missing, key);
        return value ? yesScore : 0;
    }

    private static Integer missing(List<String> missing, String key) {
        missing.add(key);
        return null;
    }

    private static String value(String raw) {
        return raw == null ? "" : raw.trim();
    }

    record Result(Integer total, String status, List<String> missingItems, Breakdown breakdown, String policyVersion) {}

    record Breakdown(
            Integer quality,
            Integer delivery,
            Integer afterSales,
            Integer hotProduct,
            Integer freeSample,
            Integer priceLevel,
            Integer invoice
    ) {}
}
