package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Validate only new quotations. Old records and idempotent retries keep their fee snapshot. */
public final class CustomerOperationFees {
    private CustomerOperationFees() {}
    public static void validate(JsonNode settings, ObjectNode quotation) {
        var snapshot = quotation.path("customerOperation");
        // Free text, even an identical customer name, does not select a finance customer.
        if (snapshot.isMissingNode() || snapshot.isNull()) return;
        if (!snapshot.isObject() || !snapshot.path("id").isTextual() || snapshot.path("id").asText().isBlank()
                || !snapshot.path("feeUsd").isNumber()) throw AppException.unprocessable("客户操作费快照不完整，请重新选择客户");
        for (var customer : settings.path("customers")) {
            if (!customer.path("id").asText().equals(snapshot.path("id").asText())) continue;
            if (!customer.path("enabled").asBoolean() || !customer.path("name").asText().trim().equals(quotation.path("customerName").asText().trim())
                    || !customer.path("name").asText().trim().equals(snapshot.path("name").asText())
                    || customer.path("feeUsd").decimalValue().compareTo(snapshot.path("feeUsd").decimalValue()) != 0)
                throw AppException.conflict("客户操作费设置已变化，请重新加载并选择客户后计价");
            return;
        }
        throw AppException.conflict("所选客户已删除，请重新选择客户或改为手动输入");
    }
}
