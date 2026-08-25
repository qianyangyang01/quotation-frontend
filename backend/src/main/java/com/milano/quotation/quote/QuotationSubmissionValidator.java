package com.milano.quotation.quote;

import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.FieldValidationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
        validateBundle(errors, input);
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

    private static void validateBundle(ArrayList<ApiResponse.FieldError> errors, ObjectNode input) {
        var mode = input.path("quoteMode").asText("");
        var items = input.path("bundleItems");
        if ("single".equals(mode)) {
            if (items.isArray() && !items.isEmpty()) addOnce(errors, "bundleItems", "单品报价不能包含组合商品明细");
            return;
        }
        if (!"bundle".equals(mode)) return;
        if (!items.isArray() || items.size() < 2) {
            addOnce(errors, "bundleItems", "组合报价至少需要两个不同商品");
            return;
        }

        var skus = new ArrayList<String>();
        var unique = new HashSet<String>();
        for (var item : items) {
            var sku = normalizeSku(item.path("sku").asText(""));
            if (sku.isEmpty() || !unique.add(sku)) addOnce(errors, "bundleItems", "组合商品 SKU 不能为空且不能重复");
            else skus.add(sku);
            if (!item.path("name").isTextual() || item.path("name").asText("").trim().isEmpty())
                addOnce(errors, "bundleItems", "组合商品名称不能为空");
            if (!item.path("quantityPerSet").isIntegralNumber() || item.path("quantityPerSet").asLong() <= 0)
                addOnce(errors, "bundleItems", "组合商品单套数量必须为正整数");
            if (!positiveNumber(item.path("effectiveWeightKg")))
                addOnce(errors, "bundleItems", "组合商品有效重量必须大于零");
            if (!nonNegativeNumber(item.path("purchaseUnitPriceCny")) || !nonNegativeNumber(item.path("domesticFreightPerUnitCny")))
                addOnce(errors, "bundleItems", "组合商品成本和国内运费不能为负数");
        }
        if (unique.size() < 2) addOnce(errors, "bundleItems", "组合报价至少需要两个不同商品");
        var primarySkus = List.of(input.path("primarySku").asText("").split("[,，、+\\s]+"))
                .stream().map(QuotationSubmissionValidator::normalizeSku).filter(value -> !value.isEmpty()).toList();
        if (!primarySkus.equals(skus)) addOnce(errors, "primarySku", "组合报价 SKU 必须与商品明细顺序一致");
    }

    private static String normalizeSku(String value) { return value.trim().toUpperCase(Locale.ROOT); }
    private static boolean positiveNumber(tools.jackson.databind.JsonNode value) {
        return value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() > 0;
    }
    private static boolean nonNegativeNumber(tools.jackson.databind.JsonNode value) {
        return value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= 0;
    }
    private static void addOnce(ArrayList<ApiResponse.FieldError> errors, String field, String message) {
        if (errors.stream().noneMatch(error -> error.field().equals(field) && error.message().equals(message)))
            errors.add(new ApiResponse.FieldError(field, message));
    }
}
