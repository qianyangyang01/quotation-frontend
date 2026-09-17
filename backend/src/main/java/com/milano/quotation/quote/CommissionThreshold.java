package com.milano.quotation.quote;

import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.FieldValidationException;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;

final class CommissionThreshold {
    private CommissionThreshold() {}
    static void normalize(ObjectNode input) {
        if (!input.has("commissionThreshold")) { input.put("commissionThreshold", 1); return; }
        var value = input.path("commissionThreshold");
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() <= 0 || value.asDouble() > 1)
            throw new FieldValidationException(List.of(new ApiResponse.FieldError("commissionThreshold", "佣金阈值必须大于0且不超过1")));
    }
}
