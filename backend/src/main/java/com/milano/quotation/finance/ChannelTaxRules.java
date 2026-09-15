package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import com.milano.quotation.common.EuropeanUnion;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Set;

public final class ChannelTaxRules {
    private ChannelTaxRules() {}
    public static void validateSettings(JsonNode country) {
        if (!country.has("channelRules")) return;
        if (!country.path("channelRules").isArray() || country.path("channelRules").size() > 2000) throw AppException.unprocessable("渠道税费须为列表，最多2000个渠道");
        var keys = new HashSet<String>();
        for (var rule : country.path("channelRules")) {
            var key = rule.path("key").asText();
            if (!key.matches("[0-9]+::[^:]+::[^:]+") || !keys.add(key)) throw AppException.unprocessable("渠道税费标识无效或重复");
            if (!Set.of("fixed-order","weight","exempt","no-tax","unavailable").contains(rule.path("mode").asText()) || !Set.of("USD","CNY","EUR").contains(rule.path("currency").asText())) throw AppException.unprocessable("渠道税费方式或币种无效");
            for (var name : Set.of("amount","perKg")) if (!rule.path(name).isNumber() || rule.path(name).decimalValue().signum() < 0 || rule.path(name).decimalValue().compareTo(new BigDecimal("1000000")) > 0) throw AppException.unprocessable("渠道税费必须为有效非负金额，最多1000000");
            boolean eu=EuropeanUnion.TAX_GROUP.equals(country.path("country").asText()) || EuropeanUnion.contains(country.path("country").asText());
            var parts=key.split("::",3);
            if (eu && parts.length==3 && "云途".equals(parts[1]) && Set.of("C-600c364a09421e97a32f","C-f79790fa71c225481346","C-12d8524ab88e7866389a").contains(parts[2])
                && !("weight".equals(rule.path("mode").asText()) && "EUR".equals(rule.path("currency").asText()) && rule.path("amount").decimalValue().compareTo(new BigDecimal("0.6"))==0 && rule.path("perKg").decimalValue().compareTo(new BigDecimal("1.5"))==0)) throw AppException.unprocessable("欧盟三条 CHC 渠道须保留欧元重量公式");

        }
    }
    private static String countryKey(String value) {
        return switch(value.trim()) { case "美国"->"US"; case "新西兰"->"NZ"; case "墨西哥"->"MX"; case "阿联酋","阿拉伯联合酋长国"->"AE"; case "沙特阿拉伯"->"SA"; case "哥伦比亚"->"CO"; case "约旦"->"JO"; case "摩洛哥"->"MA"; case "阿曼"->"OM"; default->value.trim().toUpperCase(java.util.Locale.ROOT); };
    }
    static JsonNode matching(JsonNode settings, String country, String key) {
        JsonNode matched = null;
        for (var row : settings.path("countries")) if (row.path("selected").asBoolean() && (EuropeanUnion.sameCountry(row.path("country").asText(),country) || countryKey(row.path("country").asText()).equals(countryKey(country)))) { matched=row; break; }
        if (matched == null && EuropeanUnion.contains(country)) for (var row : settings.path("countries")) if (row.path("selected").asBoolean() && EuropeanUnion.TAX_GROUP.equals(row.path("country").asText())) { matched=row; break; }
        if (matched != null) for (var row : matched.path("channelRules")) if (key.equals(row.path("key").asText())) return row;
        return null;
    }
    public static boolean validateQuote(JsonNode settings, JsonNode option, JsonNode exchange, int customQuantity) {
        var rule = matching(settings, option.path("country").asText(), option.path("channelKey").asText());
        if (rule == null) return false;
        var mode=rule.path("mode").asText();
        if (mode.equals("unavailable")) throw AppException.conflict("该渠道不支持当前国家，请移除后重新报价");
        var expectedMode=mode.equals("weight")?"weight-order":mode;
        if (!option.path("taxConfigured").asBoolean() || !expectedMode.equals(option.path("taxFeeMode").asText()) || option.path("taxIncluded").asBoolean()!=mode.equals("exempt")) changed();
        var expectedFixed=mode.equals("fixed-order")?amount(rule, BigDecimal.ZERO,exchange):BigDecimal.ZERO;
        if (!option.path("countryFixedTaxUsd").isNumber() || expectedFixed.compareTo(option.path("countryFixedTaxUsd").decimalValue())!=0) changed();
        if (!option.path("taxCalculations").isObject() || option.path("taxCalculations").isEmpty()) changed();
        for (var entry : option.path("taxCalculations").properties()) {
            var snapshot=entry.getValue();
            if (!snapshot.path("rule").asText().equals("channel-tax-v1")) changed();
            for (var field : Set.of("key","mode","amount","perKg","currency")) {
                var a=rule.path(field); var b=snapshot.path("setting").path(field);
                if (a.isNumber() ? !b.isNumber() || a.decimalValue().compareTo(b.decimalValue())!=0 : !a.equals(b)) changed();
            }
            if (mode.equals("weight")) {
                if (!snapshot.path("weightKg").isNumber() || snapshot.path("weightKg").asDouble()<=0) changed();
                boolean matchedWeight=false;
                for (var sample:option.path("logisticsSamples")) if (entry.getKey().equals(sample.path("quantity").asText()) && !sample.path("total").isNull() && sample.path("input").path("weightKg").isNumber() && sample.path("input").path("weightKg").decimalValue().compareTo(snapshot.path("weightKg").decimalValue())==0) matchedWeight=true;
                if (!matchedWeight) changed();
            }
            var expected=amount(rule,snapshot.path("weightKg").decimalValue(),exchange);
            if (!snapshot.path("taxUsd").isNumber() || expected.compareTo(snapshot.path("taxUsd").decimalValue())!=0) changed();
        }
        for (var name : Set.of("tax1Usd","tax2Usd","tax3Usd","taxCustomUsd")) {
            if (!option.hasNonNull(name)) continue;
            if (!option.path(name).isNumber()) changed();
            var quantity=name.equals("taxCustomUsd")?String.valueOf(customQuantity):name.substring(3,4);
            var snapshot=option.path("taxCalculations").path(quantity);
            if (!snapshot.path("taxUsd").isNumber() || snapshot.path("taxUsd").decimalValue().compareTo(option.path(name).decimalValue())!=0) changed();
            if (!mode.equals("weight") && amount(rule,BigDecimal.ZERO,exchange).compareTo(option.path(name).decimalValue())!=0) changed();
        }
        return true;
    }
    static BigDecimal amount(JsonNode rule, BigDecimal weight, JsonNode exchange) {
        var mode=rule.path("mode").asText();
        if (mode.equals("no-tax") || mode.equals("exempt")) return BigDecimal.ZERO;
        var value=rule.path("amount").decimalValue().add(mode.equals("weight")?weight.multiply(rule.path("perKg").decimalValue()):BigDecimal.ZERO);
        if (rule.path("currency").asText().equals("CNY")) {
            var rate=exchange.path("usdCny").isNumber()?exchange.path("usdCny"):exchange.path("usdToCny");
            if (!rate.isNumber() || rate.asDouble()<=0) changed();
            value=value.divide(rate.decimalValue(),12,RoundingMode.HALF_UP);
        } else if (rule.path("currency").asText().equals("EUR")) {
            if (!exchange.path("eurUsd").isNumber() || exchange.path("eurUsd").asDouble()<=0) changed();
            value=value.multiply(exchange.path("eurUsd").decimalValue());
        }
        return value.setScale(2,RoundingMode.HALF_UP);
    }
    private static void changed() { throw AppException.conflict("渠道税费或汇率已变化，请重新计价后提交"); }
}
