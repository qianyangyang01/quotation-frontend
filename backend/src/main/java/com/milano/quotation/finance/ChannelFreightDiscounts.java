package com.milano.quotation.finance;

import com.milano.quotation.common.AppException;
import com.milano.quotation.common.CountryIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.*;

/** Applies finance rules to copies of quotation prices, never to stored tariffs or history. */
public final class ChannelFreightDiscounts {
    private ChannelFreightDiscounts() {}
    public static void validate(JsonNode settings) {
        if (!settings.path("rules").isArray() || settings.path("rules").size()>1000) throw AppException.unprocessable("渠道折扣须为列表，最多1000个渠道");
        var ids=new HashSet<String>();
        for (var rule:settings.path("rules")) {
            var id=rule.path("channelId").asText();
            try { if (!UUID.fromString(id).toString().equals(id)||!ids.add(id)) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException invalid) { throw AppException.unprocessable("折扣渠道标识无效或重复"); }
            if (!rule.path("enabled").isBoolean() || !Set.of("full-freight","base-excluding-linehaul").contains(rule.path("basis").asText())
                    || !rule.path("countries").isObject() || rule.path("countries").size()>250) throw AppException.unprocessable("渠道运费折扣设置无效");
            factor(rule.path("defaultFactor"));
            for(var entry:rule.path("countries").properties()) {
                if (!entry.getKey().matches("[A-Z]{2}")) throw AppException.unprocessable("折扣国家须使用两位国家代码");
                factor(entry.getValue());
            }
        }
    }
    public static JsonNode matching(JsonNode settings,String channelId) {
        for(var rule:settings.path("rules")) if(rule.path("channelId").asText().equals(channelId)&&rule.path("enabled").asBoolean()) return rule;
        return null;
    }
    public static List<String> sourceChannels(JsonNode settings) {
        var ids=new ArrayList<String>();
        for(var rule:settings.path("rules")) if(rule.path("enabled").asBoolean()&&rule.path("basis").asText().equals("base-excluding-linehaul")) ids.add(rule.path("channelId").asText());
        return ids;
    }
    private static BigDecimal factor(JsonNode value) {
        if(!value.isNumber()||value.decimalValue().signum()<=0||value.decimalValue().compareTo(new BigDecimal("2"))>0) throw AppException.unprocessable("运费折扣系数须大于0且不超过2");
        return value.decimalValue();
    }
    public static ObjectNode apply(JsonNode source,String channelId,JsonNode settings) {
        var row=(ObjectNode)source.deepCopy();var rule=matching(settings,channelId);
        if(rule==null) return row;
        var model=source.path("pricingModel").asText("per-kg");
        if (!Set.of("per-kg","per-kg-1g","per-piece-500g").contains(model)||source.has("financeFreightDiscount")) return unavailable(row);
        var code=source.path("countryCode").asText();
        var country=CountryIdentity.key(code.isBlank()?source.path("areaName").asText():code);
        var configured=rule.path("countries").path(country);
        var discount=factor(configured.isMissingNode()?rule.path("defaultFactor"):configured);
        if(!source.path("registrationFee").isNumber()||source.path("registrationFee").decimalValue().signum()<0) return unavailable(row);
        var fee=source.path("registrationFee").decimalValue();
        var priceField=model.equals("per-piece-500g")?"intervalPrice":"pricePerKg";
        if(!source.path(priceField).isNumber()||source.path(priceField).decimalValue().signum()<0) return unavailable(row);
        var base=source.path(priceField).decimalValue();var linehaul=BigDecimal.ZERO;
        if(rule.path("basis").asText().equals("base-excluding-linehaul")) {
            if(model.equals("per-piece-500g")||source.has("sourceDiscountPolicy")||!source.path("sourcePricePerKg").isNumber()||!source.path("sourceLinehaulPerKg").isNumber()) return unavailable(row);
            base=source.path("sourcePricePerKg").decimalValue();linehaul=source.path("sourceLinehaulPerKg").decimalValue();
            if(base.signum()<0||linehaul.signum()<0||base.add(linehaul).compareTo(source.path("pricePerKg").decimalValue())!=0) return unavailable(row);
        }
        row.put(priceField,base.multiply(discount).add(linehaul)).put("registrationFee",fee.multiply(discount));
        row.putObject("financeFreightDiscount").put("channelId",channelId).put("basis",rule.path("basis").asText()).put("factor",discount)
            .put("originalBasePrice",base).put("priceField",priceField).put("originalRegistrationFee",fee).put("linehaulPerKg",linehaul);
        return row;
    }
    private static ObjectNode unavailable(ObjectNode row) {
        return row.put("quoteReady",false).put("pendingReason","暂停报价：运费折扣缺少原费用/干线费依据，或计费方式不适用，请财务核对");
    }
    /** Validate new enabled bindings at publish time; later tariff changes are checked again on every quote. */
    public static void validateBindings(JdbcClient jdbc,JsonNode settings) {
        validateBindings(jdbc,settings,new ObjectMapper().createObjectNode());
    }
    public static void validateBindings(JdbcClient jdbc,JsonNode settings,JsonNode previous) {
        var mapper=new ObjectMapper();
        for(var rule:settings.path("rules")) {
            if(!rule.path("enabled").asBoolean()) continue;
            var id=rule.path("channelId").asText();
            if(rule.equals(matching(previous,id))) continue;
            var rows=jdbc.sql("""
                select item::text from logistics_channel c join logistics_provider p on p.id=c.provider_id
                join logistics_version v on v.id=c.current_version_id and v.status='published'
                cross join lateral jsonb_array_elements(v.payload->'rows') item
                where c.id=cast(:id as uuid) and c.dataset_id=logistics_active_dataset() and c.archived_at is null
                  and coalesce((c.payload->>'enabled')::boolean,true) and coalesce((p.payload->>'enabled')::boolean,true)
                  and logistics_company_quote_allowed(c.id) and logistics_version_quote_ready(v.id)
                  and logistics_price_row_quote_supported(item)
                """).param("id",id).query(String.class).list();
            if(rows.isEmpty()) throw AppException.unprocessable("折扣渠道未发布、已停用或无可报价运价，请刷新渠道目录");
            for(var raw:rows) if(!apply(mapper.readTree(raw),id,settings).path("quoteReady").asBoolean(true))
                throw AppException.unprocessable("渠道运价不支持所选折扣范围，或缺少干线费拆分依据，请核对后再发布");
        }
    }
}
