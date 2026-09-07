package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import tools.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

final class FinanceSettingValidation {
    private FinanceSettingValidation() {}
    static void validate(String key, JsonNode body) {
        if (body == null || body.isNull()) fail("财务设置不能为空");
        switch (key) {
            case "exchange-rate" -> {
                object(body); var field = body.has("usdCny") ? "usdCny" : "usdToCny";
                number(body.path(field), field, true);
                if (body.has("usdCny") && body.has("usdToCny")) number(body.path("usdToCny"), "usdToCny", true);
            }
            case "customer-grades" -> {
                var rows = array(body, "grades"); var seen = new HashSet<String>();
                for (var row : rows) { object(row); unique(row, "grade", seen); if (!Set.of("S","A","B","C","D","E").contains(row.path("grade").asText())) fail("客户等级不合法"); number(row.path("coefficient"), "coefficient", true); }
            }
            case "country-classification" -> {
                var seen = new HashSet<String>(); for (var row : array(body,"countries")) { object(row); unique(row,"country",seen); }
            }
            case "channel-policies" -> {
                var seen = new HashSet<String>(); for (var policy : array(body,"policies")) {
                    object(policy); unique(policy,"category",seen); var countries = new HashSet<String>();
                    if (!policy.path("countryRules").isArray()) fail("国家渠道配置必须为列表");
                    for (var row : policy.path("countryRules")) { object(row); unique(row,"country",countries);
                        if (!row.path("allowedChannels").isArray()) fail("允许渠道必须为列表");
                        for (var channel : row.path("allowedChannels")) if (!channel.isTextual() || channel.asText().isBlank()) fail("允许渠道标识不合法");
                    }
                }
            }
            case "tax-settings" -> {
                object(body);
                if (body.has("rules") && !body.has("countries") && !body.has("providers")) { if (!body.path("rules").isArray()) fail("税费规则必须为列表"); }
                else {
                    if (!body.path("countries").isArray() || !body.path("providers").isArray()) fail("税费国家及物流商配置必须为列表");
                    var countries = new HashSet<String>(); for (var row : body.path("countries")) { object(row); unique(row,"country",countries); }
                    var providers = new HashSet<String>(); for (var row : body.path("providers")) { object(row); unique(row,"provider",providers); }
                }
            }
            default -> fail("财务设置不存在");
        }
        validateValues(body);
    }
    private static void validateValues(JsonNode node) {
        if (node.isArray()) { for (var child : node) validateValues(child); return; }
        if (!node.isObject()) return;
        for (var entry : node.properties()) {
            var key = entry.getKey(); var value = entry.getValue();
            if (Set.of("fixedFeeUsd","aFixedFeeUsd","bPerItemFeeUsd","ratePercent","sortOrder").contains(key)) number(value,key,false);
            if (Set.of("enabled","selected").contains(key) && !value.isBoolean()) fail(key+"必须为布尔值");
            validateValues(value);
        }
    }
    private static JsonNode array(JsonNode node,String legacyKey) { var rows=node.isArray()?node:node.path(legacyKey); if(!rows.isArray()) fail("财务设置必须为列表"); return rows; }
    private static void object(JsonNode node) { if(!node.isObject()) fail("财务设置行格式不正确"); }
    private static void unique(JsonNode node,String key,Set<String> seen) { var value=node.path(key); if(!value.isTextual()||value.asText().isBlank()||!seen.add(value.asText().trim())) fail(key+"不能为空或重复"); }
    private static void number(JsonNode node,String field,boolean positive) { if(!node.isNumber()||!Double.isFinite(node.asDouble())||(positive?node.asDouble()<=0:node.asDouble()<0)) fail(field+(positive?"必须为有效正数":"必须为有效非负数")); }
    private static void fail(String message) { throw AppException.unprocessable(message); }
}
