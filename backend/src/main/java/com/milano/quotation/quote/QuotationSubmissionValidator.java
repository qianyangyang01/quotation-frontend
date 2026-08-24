package com.milano.quotation.quote;

import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.FieldValidationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Set;

@Component
public class QuotationSubmissionValidator {
    private static final Set<String> MODES = Set.of("single", "bundle");
    private static final Set<String> CATEGORIES = Set.of("文胸", "内裤", "袜子", "服装", "保健品", "化妆品");
    private static final Set<String> GRADES = Set.of("S级客户", "A级客户", "B级客户", "C级客户", "D级客户", "E级客户");
    private static final Set<String> TAX_TYPES = Set.of("A", "B");
    private static final Set<String> SALES = Set.of("10", "100", "100+");

    public void validate(ObjectNode input) {
        var errors = new ArrayList<ApiResponse.FieldError>();
        required(errors, input, "customerName", "客户名称不能为空", 120);
        oneOf(errors, input, "quoteMode", MODES, "报价模式不合法");
        required(errors, input, "primarySku", "请选择正式采购商品", 2000);
        oneOf(errors, input, "productCategory", CATEGORIES, "请选择有效的产品品类");
        required(errors, input, "logisticsAttribute", "物流属性不能为空", 60);
        oneOf(errors, input, "customerGrade", GRADES, "客户等级不合法");
        oneOf(errors, input, "taxCustomerType", TAX_TYPES, "税费客户类型不合法");
        oneOf(errors, input, "monthlySalesEstimate", SALES, "预估月销量不合法");
        if (!input.path("quoteOptions").isArray() || input.path("quoteOptions").isEmpty()) {
            errors.add(new ApiResponse.FieldError("quoteOptions", "请至少选择一条报价渠道"));
        }
        if (!errors.isEmpty()) throw new FieldValidationException(errors);
    }

    private static void required(ArrayList<ApiResponse.FieldError> errors, ObjectNode input, String field, String message, int max) {
        var value = input.path(field).asText("").trim();
        if (value.isEmpty() || value.length() > max) errors.add(new ApiResponse.FieldError(field, message));
    }
    private static void oneOf(ArrayList<ApiResponse.FieldError> errors, ObjectNode input, String field, Set<String> allowed, String message) {
        if (!allowed.contains(input.path(field).asText(""))) errors.add(new ApiResponse.FieldError(field, message));
    }
}
