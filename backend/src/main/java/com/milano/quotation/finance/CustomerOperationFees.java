package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Validate only new quotations. Old records and idempotent retries keep their fee snapshot. */
public final class CustomerOperationFees {
    private CustomerOperationFees() {}
    private static final java.util.List<String> TIERS = java.util.List.of("1", "2", "3", "above3");
    private static java.math.BigDecimal amount(JsonNode value) {
        if (!value.isNumber()) throw AppException.unprocessable("四档操作费均须填写有效金额");
        var decimal = value.decimalValue();
        if (decimal.signum() < 0 || decimal.compareTo(new java.math.BigDecimal("1000000")) > 0
                || decimal.stripTrailingZeros().scale() > 2)
            throw AppException.unprocessable("操作费须为0～1000000美元，最多两位小数");
        return decimal;
    }
    static java.util.List<java.math.BigDecimal> amounts(JsonNode row) {
        if (!row.has("feesByQuantityUsd")) return java.util.Collections.nCopies(4, amount(row.path("feeUsd")));
        var tiers = row.path("feesByQuantityUsd");
        if (!tiers.isObject()) throw AppException.unprocessable("四档操作费快照不完整");
        var fees = TIERS.stream().map(key -> amount(tiers.path(key))).toList();
        if (row.has("feeUsd") && amount(row.path("feeUsd")).compareTo(fees.get(0)) != 0)
            throw AppException.unprocessable("操作费兼容金额与1件档不一致");
        return fees;
    }
    private static boolean sameAmounts(JsonNode left, JsonNode right) {
        var a = amounts(left); var b = amounts(right);
        for (int i = 0; i < a.size(); i++) if (a.get(i).compareTo(b.get(i)) != 0) return false;
        return true;
    }
    // A stale client must not flatten an existing tiered configuration on save.
    static void preventLegacyOverwrite(JsonNode before, JsonNode after) {
        for (var old : before.path("customers")) {
            if (!old.has("feesByQuantityUsd")) continue;
            for (var next : after.path("customers")) {
                if (old.path("id").asText().equals(next.path("id").asText())
                        && !next.has("feesByQuantityUsd") && !sameAmounts(old, next))
                    throw AppException.conflict("客户操作费已升级为四档，请刷新页面后修改");
            }
        }
    }
    public static void validate(JsonNode settings, ObjectNode quotation) {
        var snapshot = quotation.path("customerOperation");
        // Free text, even an identical customer name, does not select a finance customer.
        if (snapshot.isMissingNode() || snapshot.isNull()) return;
        if (!snapshot.isObject() || !snapshot.path("id").isTextual() || snapshot.path("id").asText().isBlank()) throw AppException.unprocessable("客户操作费快照不完整，请重新选择客户");
        amounts(snapshot);
        for (var customer : settings.path("customers")) {
            if (!customer.path("id").asText().equals(snapshot.path("id").asText())) continue;
            if (!customer.path("enabled").asBoolean() || !customer.path("name").asText().trim().equals(quotation.path("customerName").asText().trim())
                    || !customer.path("name").asText().trim().equals(snapshot.path("name").asText())
                    || !sameAmounts(customer, snapshot))
                throw AppException.conflict("客户操作费设置已变化，请重新加载并选择客户后计价");
            return;
        }
        throw AppException.conflict("所选客户已删除，请重新选择客户或改为手动输入");
    }
}
