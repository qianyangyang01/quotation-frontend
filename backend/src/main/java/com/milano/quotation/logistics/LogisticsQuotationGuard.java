package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

/** Called inside quotation creation's transaction. Historical records are never revalidated. */
@Service
public class LogisticsQuotationGuard {
    private final JdbcClient jdbc;
    private final LogisticsQueryService queries;
    private final ObjectMapper mapper;
    public LogisticsQuotationGuard(JdbcClient jdbc,LogisticsQueryService queries,ObjectMapper mapper){this.jdbc=jdbc;this.queries=queries;this.mapper=mapper;}
    public void validate(ObjectNode quotation) {
        if(jdbc.sql("select paused from logistics_company_state where singleton=true for share").query(Boolean.class).single())throw AppException.conflict("物流价格正在重建，暂时不能提交新报价");
        boolean scoped="selected".equals(quotation.path("logisticsSyncScope").asText());
        if(!quotation.path("quoteOptions").isArray()||quotation.path("quoteOptions").size()>100)throw AppException.unprocessable("报价渠道数量不合法");
        var requestedKeys = new ArrayList<String>();
        quotation.path("quoteOptions").forEach(option -> requestedKeys.add(option.path("channelKey").asText()));
        if (requestedKeys.isEmpty()) throw AppException.unprocessable("请至少选择一条报价渠道");
        var dataset=jdbc.sql("select id from logistics_dataset where status='active' for share").query(UUID.class).single();
        jdbc.sql("""
            select c.id from logistics_channel c join logistics_provider p on p.id=c.provider_id
            where c.dataset_id=:id and concat(c.rule_id,'::',p.payload->>'name','::',c.code) in (:keys)
            order by c.id for share of c,p
            """).param("id",dataset).param("keys",requestedKeys).query(UUID.class).list();
        var revision=queries.manifestRevision().revision();
        if(!scoped&&!revision.equals(quotation.path("logisticsRevision").asText()))throw AppException.conflict("物流版本已更新或缺少版本信息，请重新加载并计价后提交");
        var policies=mapper.readTree(jdbc.sql("select payload::text from finance_setting where setting_key='channel-policies' for share").query(String.class).optional().orElse("[]"));
        var surcharges=mapper.readTree(jdbc.sql("select payload::text from finance_setting where setting_key='surcharge-settings' for share").query(String.class).optional().orElse("{}"));
        var channels=jdbc.sql("""
            select jsonb_build_object('key',concat(c.rule_id,'::',p.payload->>'name','::',c.code),
                'versionId',v.id,'channelId',c.id,'rows',v.quote_rows,
                'legacy',exists(select 1 from logistics_billing_acceptance a where a.version_id=v.id and a.kind='legacy' and a.rows_fingerprint=v.rows_fingerprint))::text
            from logistics_channel c join logistics_provider p on p.id=c.provider_id
            join logistics_version v on v.id=c.current_version_id and v.status='published'
            where c.dataset_id=:id and c.archived_at is null and logistics_company_quote_allowed(c.id)
            and coalesce((c.payload->>'enabled')::boolean,true) and coalesce((p.payload->>'enabled')::boolean,true)
            and logistics_version_quote_ready(v.id)
            and concat(c.rule_id,'::',p.payload->>'name','::',c.code) in (:keys)
            """).param("id",dataset).param("keys",requestedKeys).query((rs,n)->mapper.readTree(rs.getString(1))).list();
        for(var option:quotation.path("quoteOptions")) {
            var key=option.path("channelKey").asText();var country=option.path("country").asText();
            var channel=channels.stream().filter(c->c.path("key").asText().equals(key)).findFirst().orElseThrow(()->AppException.conflict("报价渠道已归档、未适配或不存在，请重新选择"));
            validateSurcharge(surcharges, option);
            boolean countryAvailable=false;
            for(var row:channel.path("rows"))if(row.path("areaName").asText().equals(country)||row.path("countryCode").asText().equalsIgnoreCase(country))countryAvailable=true;
            if(!countryAvailable||!allowed(policies,quotation.path("logisticsAttribute").asText(),country,key))throw AppException.unprocessable("渠道不在该国家及货物属性的财务允许范围内");
            if(scoped||!channel.path("legacy").asBoolean()){
                if((!scoped&&!option.path("logisticsVersionId").asText().equals(channel.path("versionId").asText()))||!option.path("logisticsChannelId").asText().equals(channel.path("channelId").asText()))throw AppException.conflict("缺少当前渠道版本，请重新计价确认");
                var input=option.path("logisticsInput");
                if(!input.isObject())throw AppException.unprocessable("缺少重新计价输入");
                if(!input.path("country").asText().equals(country))throw AppException.unprocessable("计费输入国家与报价国家不一致");
                var normalized=(ObjectNode)input.deepCopy();normalized.putArray("marks").add(quotation.path("logisticsAttribute").asText());
                if(normalized.path("zoneName").asText().isBlank()&&!option.path("quoteRegion").asText().isBlank())normalized.put("zoneName",option.path("quoteRegion").asText());
                var result=new LogisticsBillingEngine(mapper).calculate(channel.path("rows"),normalized);
                if(!option.path("freightCny").isNumber()||option.path("freightCny").decimalValue().compareTo(result.path("total").decimalValue())!=0)throw AppException.conflict("物流费用与服务器核算不一致，请重新计价");
                if(scoped) validateSamples(channel.path("rows"),option,quotation.path("logisticsAttribute").asText(),country);
                ((ObjectNode)option).set("logisticsCalculation",result);
            }
            ((ObjectNode)option).set("logisticsVersionId",channel.path("versionId"));
            ((ObjectNode)option).set("logisticsChannelId",channel.path("channelId"));
        }
        quotation.put("logisticsDatasetId",dataset.toString());
        quotation.put("logisticsRevision",revision);
    }
    static void validateSurcharge(JsonNode settings, JsonNode option) {
        if (!settings.path("countries").isArray()) return;
        boolean enabled = false, exempt = false, configured = true;
        var expected = java.math.BigDecimal.ZERO;
        for (var country : settings.path("countries")) {
            if (!country.path("country").asText().equals(option.path("country").asText()) || !country.path("selected").asBoolean()) continue;
            enabled = country.path("enabled").asBoolean() && country.path("fixedFeeUsd").asDouble() > 0;
            if (!enabled) break;
            if (!country.path("providers").isArray() && country.path("exemptChannelKeys").isArray()) {
                for (var key : country.path("exemptChannelKeys")) if (key.asText().equals(option.path("channelKey").asText())) exempt = true;
            } else {
                // Retain legacy behavior until this country is explicitly confirmed by finance.
                configured = false;
                var parts = option.path("channelKey").asText().split("::", 3);
                var provider = parts.length == 3 ? parts[1] : "";
                for (var row : country.path("providers").isArray() ? country.path("providers") : settings.path("providers")) if (row.path("selected").asBoolean() && row.path("provider").asText().trim().equals(provider.trim())) {
                    configured = true; exempt = "exempt".equals(row.path("mode").asText()); break;
                }
            }
            if (!exempt) expected = country.path("fixedFeeUsd").decimalValue().setScale(2, java.math.RoundingMode.HALF_UP);
            break;
        }
        if (!configured || !option.path("surchargeUsd").isNumber() || option.path("surchargeUsd").decimalValue().compareTo(expected) != 0
                || !option.path("surchargeConfigured").asBoolean() || option.path("surchargeExempt").asBoolean() != exempt
                || option.path("surchargeEnabled").asBoolean() != enabled)
            throw AppException.conflict("国家渠道附加费已变化，请重新计价后提交");
    }
    private void validateSamples(JsonNode rows,JsonNode option,String attribute,String country) {
        var samples=option.path("logisticsSamples");
        if(!samples.isArray()||samples.isEmpty()||samples.size()>5)throw AppException.unprocessable("缺少报价数量档核验信息");
        for(var sample:samples){
            if(!sample.path("input").isObject()||!sample.path("input").path("country").asText().equals(country))throw AppException.unprocessable("数量档计费国家不一致");
            var input=(ObjectNode)sample.path("input").deepCopy();input.putArray("marks").add(attribute);
            if(!input.path("zoneName").asText("").equals(option.path("quoteRegion").asText("")))throw AppException.unprocessable("数量档计费区域不一致");
            JsonNode result;
            try{result=new LogisticsBillingEngine(mapper).calculate(rows,input);}
            catch(AppException error){if(sample.path("total").isNull()&&error.status().value()==422)continue;throw AppException.conflict("当前报价数量档已不可用，请更新报价");}
            if(!sample.path("total").isNumber()||sample.path("total").decimalValue().compareTo(result.path("total").decimalValue())!=0)throw AppException.conflict("当前报价数量档运费已变化，请更新报价");
            var row=rows.get(result.path("rowIndex").asInt());
            for(var key:List.of("etaMinDays","etaMaxDays"))if(sample.path(key).asInt(0)!=row.path(key).asInt(0))throw AppException.conflict("当前渠道时效已变化，请更新报价");
        }
    }
    static boolean allowed(JsonNode policies,String attribute,String country,String key){
        int matches=0;
        for(var policy:policies)if(com.milano.quotation.common.LogisticsAttributes.normalize(policy.path("category").asText()).equals(com.milano.quotation.common.LogisticsAttributes.normalize(attribute)))matches++;
        if(matches!=1)return false;
        for(var policy:policies)if(policy.path("enabled").asBoolean()&&com.milano.quotation.common.LogisticsAttributes.normalize(policy.path("category").asText()).equals(com.milano.quotation.common.LogisticsAttributes.normalize(attribute)))
            for(var rule:policy.path("countryRules"))if(rule.path("country").asText().equals(country))
                for(var allowed:rule.path("allowedChannels"))if(allowed.asText().equals(key))return true;
        return false;
    }
}
