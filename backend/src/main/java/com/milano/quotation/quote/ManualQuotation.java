package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

/** Direct manual inputs are their own snapshot; never masquerade as a purchase SKU. */
public final class ManualQuotation {
    private ManualQuotation() {}
    public static boolean isManual(JsonNode input) {
        return List.of("freight-trial", "shipping-only").contains(input.path("quoteMode").asText());
    }
    private static BigDecimal number(JsonNode node) {
        if (node == null || !node.isNumber() || !Double.isFinite(node.asDouble()) || node.decimalValue().signum() < 0)
            throw AppException.unprocessable("手填报价的金额和重量必须为有效非负数");
        return node.decimalValue();
    }
    private static BigDecimal quantity(JsonNode node) {
        var value = number(node);
        if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 0 || value.compareTo(new BigDecimal("9007199254740991")) > 0)
            throw AppException.unprocessable("手填报价数量必须为正整数");
        return value;
    }
    private static void equal(JsonNode actual, BigDecimal expected) {
        if (number(actual).subtract(expected).abs().compareTo(new BigDecimal("0.000000000001")) > 0)
            throw AppException.unprocessable("手填报价成本或重量与输入不一致，请重新查询");
    }
    public static void validate(ObjectNode input) {
        if (!isManual(input)) {
            if (input.hasNonNull("manualPricing")) throw AppException.unprocessable("商品报价不能携带手填报价条件");
            return;
        }
        var pricing = input.path("manualPricing");
        var cost = number(pricing.path("costCny"));
        var grams = number(pricing.path("weightGrams"));
        if (cost.stripTrailingZeros().scale() > 2 || grams.stripTrailingZeros().scale() > 3
                || cost.compareTo(new BigDecimal("1000000")) > 0 || grams.compareTo(new BigDecimal("1000000")) > 0 || grams.signum() <= 0)
            throw AppException.unprocessable("手填成本最多2位小数，克重最多3位小数，均不能超过1000000且重量必须大于零");
        if ("shipping-only".equals(input.path("quoteMode").asText()) && cost.signum() != 0)
            throw AppException.unprocessable("仅代发货报价不能包含商品成本");
        if (!input.path("primarySku").asText("").isBlank() || !input.path("productCategory").asText("").isBlank()
                || input.path("bundleItems").size() > 0 || input.path("purchaseVersions").size() > 0 || input.hasNonNull("weightSnapshot"))
            throw AppException.unprocessable("手填报价不能关联采购商品或包材快照");
        for (var field : List.of("purchaseUnitPriceCny", "purchaseBaseUnitPriceCny", "domesticFreightPerUnitCny", "purchaseInvoiceRatePercent"))
            if (input.hasNonNull(field)) throw AppException.unprocessable("手填报价不能重复加入采购费用");
        if (!input.path("financeVersions").isObject() || input.path("financeVersions").isEmpty())
            throw AppException.unprocessable("手填报价缺少财务版本，请刷新后重新计价");
        var unitKg = grams.movePointLeft(3);
        for (var option : input.path("quoteOptions")) {
            var parcel = option.path("logisticsInput");
            var q = quantity(parcel.path("quantity"));
            equal(parcel.path("weightKg"), unitKg.multiply(q));
            equal(parcel.path("baseWeightKg"), unitKg.multiply(q));
            for (var field : List.of("packagingWeightKg", "standardPackagingWeightKg", "specialPackagingWeightKg"))
                equal(parcel.path(field), BigDecimal.ZERO);
            if (option.path("available").isBoolean() && !option.path("available").asBoolean()) continue;
            var samples = option.path("logisticsSamples");
            if (!samples.isArray() || samples.isEmpty()) throw AppException.unprocessable("手填报价缺少数量档核验信息");
            var quantities = new HashSet<BigDecimal>();
            var totals = new java.util.HashMap<BigDecimal, JsonNode>();
            for (var sample : samples) {
                var count = quantity(sample.path("quantity")).stripTrailingZeros();
                if (!quantities.add(count)) throw AppException.unprocessable("手填报价数量档重复");
                equal(sample.path("input").path("weightKg"), unitKg.multiply(count));
                totals.put(count, sample.path("total"));
            }
            for (var count : List.of(BigDecimal.ONE, new BigDecimal("2"), new BigDecimal("3"), quantity(input.path("customQuoteQuantity")).stripTrailingZeros()))
                if (!quantities.contains(count)) throw AppException.unprocessable("手填报价缺少必要数量档");
            var custom = quantity(input.path("customQuoteQuantity")).stripTrailingZeros();
            var customFreight = totals.get(custom);
            // Option cost/quoteCny describe the custom quantity; logisticsInput/freightCny
            // describe the adopted parcel. They must not be compared as the same quantity.
            equal(option.path("totalCostCny"), customFreight.isNull() ? BigDecimal.ZERO : cost.multiply(custom).add(number(customFreight)));
            equal(option.path("freightCny"), number(totals.get(q.stripTrailingZeros())));
            if (option.path("isPrimary").asBoolean()) equal(input.path("totalCostCny"), cost.add(number(totals.get(BigDecimal.ONE))));
            for (var count : input.path("systemQuantityQuotes").path("quantities"))
                if (!quantities.contains(quantity(count).stripTrailingZeros())) throw AppException.unprocessable("手填报价缺少客户报价单数量档");
        }
    }
}
