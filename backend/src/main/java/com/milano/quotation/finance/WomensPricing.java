package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/** New submissions only. Stored quotes are immutable pricing evidence, never repriced here. */
public final class WomensPricing {
    private WomensPricing() {}
    public static boolean isWomens(String mode) { return Set.of("womens-single", "womens-bundle").contains(mode); }
    public static boolean isBundle(String mode) { return mode.equals("bundle") || mode.equals("womens-bundle"); }
    public static boolean isSingle(String mode) { return mode.equals("single") || mode.equals("womens-single"); }
    private static BigDecimal number(JsonNode n) {
        if (!n.isNumber() || !Double.isFinite(n.asDouble())) throw AppException.unprocessable("女装计价字段须为有效数字");
        var v = new BigDecimal(n.asText());
        if (v.signum() < 0) throw AppException.unprocessable("女装计价字段不能为负数");
        return v;
    }
    public static void validatePolicy(JsonNode p) {
        if (!p.isObject() || p.path("schemaVersion").asInt() != 1 || !"total-cost-usd".equals(p.path("basis").asText()) || !p.path("enabled").isBoolean())
            throw AppException.unprocessable("女装报价系数口径无效");
        var tiers = p.path("tiers");
        if (!tiers.isArray() || tiers.isEmpty() || tiers.size() > 12) throw AppException.unprocessable("女装系数须为1至12档");
        var lower = BigDecimal.ZERO;
        for (int i = 0; i < tiers.size(); i++) {
            var t = tiers.get(i); var from = number(t.path("fromUsd")); var coefficient = number(t.path("coefficient"));
            if (from.compareTo(lower) != 0 || from.compareTo(new BigDecimal("1000000")) > 0 || coefficient.signum() <= 0 || coefficient.compareTo(BigDecimal.TEN) > 0
                    || from.stripTrailingZeros().scale() > 6 || coefficient.stripTrailingZeros().scale() > 6)
                throw AppException.unprocessable("女装区间须从0起连续，系数须大于0且不超过10，最多6位小数");
            if (i == tiers.size() - 1) { if (!t.path("toUsd").isNull()) throw AppException.unprocessable("女装最后一档须无上限"); }
            else {
                lower = number(t.path("toUsd"));
                if (lower.compareTo(from) <= 0 || lower.compareTo(new BigDecimal("1000000")) > 0 || lower.stripTrailingZeros().scale() > 6)
                    throw AppException.unprocessable("女装区间上限须大于下限且不超过1000000");
            }
        }
    }
    public static JsonNode tier(JsonNode policy, BigDecimal costCny, BigDecimal rate) {
        for (var t : policy.path("tiers")) if (costCny.compareTo(number(t.path("fromUsd")).multiply(rate)) >= 0
                && (t.path("toUsd").isNull() || costCny.compareTo(number(t.path("toUsd")).multiply(rate)) < 0)) return t;
        throw AppException.unprocessable("女装金额未命中系数档位");
    }
    public static void validateCurrent(JdbcClient jdbc, ObjectMapper mapper, ObjectNode quote) {
        if (!isWomens(quote.path("quoteMode").asText())) return;
        var current = jdbc.sql("select jsonb_build_object('policy',payload,'version',version)::text from finance_setting where setting_key='womens-pricing' for share")
                .query(String.class).optional().orElseThrow(() -> AppException.conflict("女装报价系数尚未发布"));
        var row = mapper.readTree(current);
        validateSnapshot(quote, row.path("policy"), row.path("version").asLong());
        var exchange = mapper.readTree(jdbc.sql("select payload::text from finance_setting where setting_key='exchange-rate' for share").query(String.class).single());
        equal(quote.path("exchangeRate"), number(exchange.path(exchange.has("usdCny") ? "usdCny" : "usdToCny")), "女装汇率与当前配置不一致，请更新报价");
    }
    public static void validateSnapshot(ObjectNode quote, JsonNode policy, long version) {
        validatePolicy(policy);
        if (!policy.path("enabled").asBoolean()) throw AppException.conflict("女装报价已停用");
        var snapshot = quote.path("womensPricing");
        if (!snapshot.isObject() || !snapshot.path("version").isIntegralNumber() || snapshot.path("version").asLong() != version)
            throw AppException.conflict("女装系数版本已更新，请更新报价后保存");
        for (var field : Set.of("schemaVersion", "basis", "enabled", "tiers")) if (!snapshot.path(field).equals(policy.path(field)))
            throw AppException.conflict("女装系数快照与已发布规则不一致");
        if (!"女装自动系数".equals(quote.path("customerGrade").asText())) throw AppException.unprocessable("女装报价不能使用客户等级系数");
        var rate = number(quote.path("exchangeRate"));
        if (rate.signum() <= 0) throw AppException.unprocessable("女装汇率必须大于0");
        BigDecimal unitCost = BigDecimal.ZERO;
        if (isBundle(quote.path("quoteMode").asText())) for (var item : quote.path("bundleItems"))
            unitCost = unitCost.add(number(item.path("purchaseUnitPriceCny")).add(number(item.path("domesticFreightPerUnitCny"))).multiply(number(item.path("quantityPerSet"))));
        else unitCost = number(quote.path("purchaseUnitPriceCny")).add(number(quote.path("domesticFreightPerUnitCny")));
        for (var option : quote.path("quoteOptions")) {
            if (option.path("available").isBoolean() && !option.path("available").asBoolean()) continue;
            var samples = option.path("womensPricingSamples");
            if (!samples.isArray() || samples.isEmpty() || samples.size() > 100) throw AppException.unprocessable("缺少女装各数量的系数依据");
            var seen = new HashSet<Long>();
            for (var sample : samples) {
                var q = sample.path("quantity");
                if (!q.isIntegralNumber() || q.asLong() < 1 || q.asLong() > 9007199254740991L || !seen.add(q.asLong())) throw AppException.unprocessable("女装数量无效或重复");
                JsonNode freight = null;
                for (var f : option.path("logisticsSamples")) if (f.path("quantity").asLong() == q.asLong()) { freight = f.path("total"); break; }
                if (freight == null || !freight.isNumber()) throw AppException.unprocessable("缺少女装对应数量的运费依据");
                var cost = unitCost.multiply(number(q)).add(number(freight));
                equal(sample.path("costCny"), cost, "女装综合成本与商品、数量及运费不一致");
                var band = tier(policy, cost, rate);
                equal(sample.path("coefficient"), number(band.path("coefficient")), "女装系数与金额档位不一致");
                if (!sample.path("fromUsd").equals(band.path("fromUsd")) || !sample.path("toUsd").equals(band.path("toUsd"))) throw AppException.unprocessable("女装档位说明不一致");
                equal(sample.path("baseQuoteCny"), cost.multiply(number(band.path("coefficient"))), "女装基础售价计算不一致");
            }
            for (var f : option.path("logisticsSamples")) if (f.path("total").isNumber() && !seen.contains(f.path("quantity").asLong())) throw AppException.unprocessable("女装数量系数依据不完整");
            long[] qs = {1,2,3,quote.path("customQuoteQuantity").asLong(1)};
            String[] fields = {"quote1Usd","quote2Usd","quote3Usd","quoteCustomUsd"};
            for (int i=0;i<qs.length;i++) if (option.hasNonNull(fields[i]) && !seen.contains(qs[i])) throw AppException.unprocessable("女装报价缺少对应系数依据");
            for (var r : quote.path("systemQuantityQuotes").path("rows")) if (r.path("optionId").asText().equals(option.path("id").asText())) {
                var qs2 = quote.path("systemQuantityQuotes").path("quantities");
                for (int i=0;i<qs2.size();i++) if (r.path("prices").path(i).isNumber() && !seen.contains(qs2.get(i).asLong())) throw AppException.unprocessable("女装自定义报价列缺少系数依据");
            }
        }
    }
    private static void equal(JsonNode actual, BigDecimal expected, String message) {
        if (number(actual).subtract(expected).abs().compareTo(new BigDecimal("0.00000001")) > 0) throw AppException.unprocessable(message);
    }
}
