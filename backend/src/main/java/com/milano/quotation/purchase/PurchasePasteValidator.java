package com.milano.quotation.purchase;

import com.milano.quotation.common.AppException;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;

/** Validation for data-only paste. Existing import/editor workflows keep their own rules. */
final class PurchasePasteValidator {
    static final List<String> DIMENSIONS = List.of("lengthCm", "widthCm", "heightCm");
    private PurchasePasteValidator() {}
    static void validate(ObjectNode row, int index) {
        validateValues(row, index, false);
        row.remove(List.of("_version", "productImage", "physicalImage", "image"));
        row.put("dataSource", "standard"); row.put("skuOrigin", "manual"); row.put("sourceSheet", "采购粘贴新增");
    }
    static void validateValues(ObjectNode row, int index, boolean legacy) {
        var prefix = "第" + index + "行：";
        var sku = row.path("sku").asText("").trim().toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
        if (!sku.matches("[A-Z0-9._/-]{1,96}") || sku.matches("(?i)^(TESTP|TEST|DEMO|MOCK|AUTO-).*$")) fail(prefix, "请填写有效的正式SKU");
        row.put("sku", sku);
        String[][] numbers = {{"weightG","克重"},{"minOrderQty","起订量"},{"purchasePriceCny","基准采购单价"},{"lengthCm","长"},{"widthCm","宽"},{"heightCm","高"},{"tier2MinQty","阶梯价2起订量"},{"tier2PriceCny","阶梯价2"},{"tier3MinQty","阶梯价3起订量"},{"tier3PriceCny","阶梯价3"},{"tier4MinQty","阶梯价4起订量"},{"tier4PriceCny","阶梯价4"},{"tier5MinQty","阶梯价5起订量"},{"tier5PriceCny","阶梯价5"},{"singleFreightCny","1件总运费"},{"freight10Cny","10件总运费"},{"freight100Cny","100件总运费"},{"taxIncludedPriceCny","含票价"},{"taxPoint","票点"}};
        for (var pair : numbers) if (row.hasNonNull(pair[0]) && (!row.path(pair[0]).isNumber() || !Double.isFinite(row.path(pair[0]).asDouble()) || row.path(pair[0]).asDouble() < 0)) fail(prefix, pair[1] + "须为有效非负数字");
        for (var field : List.of("weightG", "minOrderQty")) if (!row.hasNonNull(field) || row.path(field).asDouble() <= 0) fail(prefix, field.equals("weightG") ? "克重须大于0" : "起订量须大于0");
        if (!row.hasNonNull("purchasePriceCny")) fail(prefix, "请填写基准采购单价");
        for (var field : List.of("minOrderQty", "tier2MinQty", "tier3MinQty", "tier4MinQty", "tier5MinQty")) if (row.hasNonNull(field)) {
            var value = row.path(field).asDouble();
            if (value <= 0 || value != Math.floor(value) || value > 9007199254740991d) fail(prefix, "起订量须为正整数");
        }
        for (var fields : new String[][]{{"tier2MinQty","tier2PriceCny","minOrderQty"},{"tier3MinQty","tier3PriceCny","tier2MinQty"},{"tier4MinQty","tier4PriceCny","tier3MinQty"},{"tier5MinQty","tier5PriceCny","tier4MinQty"}}) {
            if (row.hasNonNull(fields[0]) != row.hasNonNull(fields[1])) fail(prefix, "阶梯起订量与价格须一起填写");
            if (row.hasNonNull(fields[0]) && (!row.hasNonNull(fields[2]) || row.path(fields[0]).asDouble() <= row.path(fields[2]).asDouble())) fail(prefix, "阶梯起订量须逐档递增，请按顺序填写");
        }
        // Legacy zero dimensions represent missing measurements. Do not rewrite stored values during validation.
        if (DIMENSIONS.stream().anyMatch(f -> row.hasNonNull(f) && row.path(f).asDouble() > 0)
                && DIMENSIONS.stream().anyMatch(f -> !row.hasNonNull(f) || row.path(f).asDouble() <= 0))
            fail(prefix, "长宽高须一起填写且大于0；也可全部留空（0按空值处理）");
        var free = row.path("freeShipping").asText("");
        if (!List.of("", "是", "否").contains(free)) fail(prefix, "是否包邮请填写是或否");
        if (!free.equals("是") && (!row.hasNonNull("singleFreightCny") || (!legacy && !row.hasNonNull("freight10Cny")))) fail(prefix, legacy ? "未包邮时须填写1件总运费" : "未包邮时须填写1件及10件总运费");
        if (!row.hasNonNull("taxPoint")) fail(prefix, "请填写票点；无票点请明确填0%");
        if (row.hasNonNull("taxPoint") && row.path("taxPoint").asDouble() > 1) fail(prefix, "票点须在0%至100%之间");
        if (row.hasNonNull("stockStatus") && !row.path("stockStatus").isTextual()) fail(prefix, "是否有货请填写文字说明");
        var date = row.path("quotationDate").asText("");
        if (!date.isBlank()) try { if (!java.time.LocalDate.parse(date).toString().equals(date)) fail(prefix, "报价日期格式不合法"); } catch (java.time.DateTimeException error) { fail(prefix, "报价日期格式不合法"); }
    }
    static void validateExtendedTiers(ObjectNode row) {
        if (!List.of("tier4MinQty", "tier4PriceCny", "tier5MinQty", "tier5PriceCny").stream().anyMatch(row::hasNonNull)) return;
        String previous = "minOrderQty";
        for (int tier = 2; tier <= 5; tier++) {
            String quantity = "tier" + tier + "MinQty", price = "tier" + tier + "PriceCny";
            if (row.hasNonNull(quantity) != row.hasNonNull(price)) fail("", "阶梯" + tier + "起订量与价格须一起填写");
            if (row.hasNonNull(quantity)) {
                var q = row.path(quantity); var p = row.path(price);
                if (!q.isNumber() || !Double.isFinite(q.asDouble()) || q.asDouble() <= 0 || q.asDouble() != Math.floor(q.asDouble()) || q.asDouble() > 9007199254740991d)
                    fail("", "阶梯起订量须为正整数");
                if (!p.isNumber() || !Double.isFinite(p.asDouble()) || p.asDouble() < 0) fail("", "阶梯价须为有效非负数字");
                if (!row.hasNonNull(previous) || q.asDouble() <= row.path(previous).asDouble()) fail("", "阶梯起订量须逐档递增，请按顺序填写");
            }
            previous = quantity;
        }
    }
    private static void fail(String prefix, String message) { throw AppException.unprocessable(prefix + message); }
}
