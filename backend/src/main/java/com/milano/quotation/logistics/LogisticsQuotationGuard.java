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
        var dataset=jdbc.sql("select id from logistics_dataset where status='active' for share").query(UUID.class).single();
        jdbc.sql("select id from logistics_channel where dataset_id=:id order by id for share").param("id",dataset).query(UUID.class).list();
        jdbc.sql("select id from logistics_provider where dataset_id=:id order by id for share").param("id",dataset).query(UUID.class).list();
        if(!queries.manifestRevision().revision().equals(quotation.path("logisticsRevision").asText()))throw AppException.conflict("物流版本已更新或缺少版本信息，请重新加载并计价后提交");
        var policies=mapper.readTree(jdbc.sql("select payload::text from finance_setting where setting_key='channel-policies' for share").query(String.class).optional().orElse("[]"));
        var channels=jdbc.sql("""
            select jsonb_build_object('key',concat(c.rule_id,'::',p.payload->>'name','::',c.code),
                'versionId',v.id,'channelId',c.id,'rows',v.payload->'rows',
                'legacy',exists(select 1 from logistics_billing_acceptance a where a.version_id=v.id and a.kind='legacy' and a.rows_fingerprint=md5(coalesce(v.payload->'rows','[]'::jsonb)::text)))::text
            from logistics_channel c join logistics_provider p on p.id=c.provider_id
            join logistics_version v on v.id=c.current_version_id and v.status='published'
            where c.dataset_id=:id and c.archived_at is null
            and coalesce((c.payload->>'enabled')::boolean,true) and coalesce((p.payload->>'enabled')::boolean,true)
            and logistics_version_quote_ready(v.id)
            """).param("id",dataset).query((rs,n)->mapper.readTree(rs.getString(1))).list();
        for(var option:quotation.path("quoteOptions")) {
            var key=option.path("channelKey").asText();var country=option.path("country").asText();
            var channel=channels.stream().filter(c->c.path("key").asText().equals(key)).findFirst().orElseThrow(()->AppException.conflict("报价渠道已归档、未适配或不存在，请重新选择"));
            boolean countryAvailable=false;
            for(var row:channel.path("rows"))if(row.path("areaName").asText().equals(country)||row.path("countryCode").asText().equalsIgnoreCase(country))countryAvailable=true;
            if(!countryAvailable||!allowed(policies,quotation.path("logisticsAttribute").asText(),country,key))throw AppException.unprocessable("渠道不在该国家及货物属性的财务允许范围内");
            if(!channel.path("legacy").asBoolean()){
                if(!option.path("logisticsVersionId").asText().equals(channel.path("versionId").asText())||!option.path("logisticsChannelId").asText().equals(channel.path("channelId").asText()))throw AppException.conflict("缺少当前渠道版本，请重新计价确认");
                var input=option.path("logisticsInput");
                if(!input.isObject())throw AppException.unprocessable("缺少重新计价输入");
                if(!input.path("country").asText().equals(country))throw AppException.unprocessable("计费输入国家与报价国家不一致");
                var normalized=(ObjectNode)input.deepCopy();normalized.putArray("marks").add(quotation.path("logisticsAttribute").asText());
                if(normalized.path("zoneName").asText().isBlank()&&!option.path("quoteRegion").asText().isBlank())normalized.put("zoneName",option.path("quoteRegion").asText());
                var result=new LogisticsBillingEngine(mapper).calculate(channel.path("rows"),normalized);
                if(!option.path("freightCny").isNumber()||option.path("freightCny").decimalValue().compareTo(result.path("total").decimalValue())!=0)throw AppException.conflict("物流费用与服务器核算不一致，请重新计价");
                ((ObjectNode)option).set("logisticsCalculation",result);
            }
            ((ObjectNode)option).set("logisticsVersionId",channel.path("versionId"));
            ((ObjectNode)option).set("logisticsChannelId",channel.path("channelId"));
        }
        quotation.put("logisticsDatasetId",dataset.toString());
    }
    static boolean allowed(JsonNode policies,String attribute,String country,String key){
        for(var policy:policies)if(policy.path("enabled").asBoolean()&&policy.path("category").asText().equals(attribute))
            for(var rule:policy.path("countryRules"))if(rule.path("country").asText().equals(country))
                for(var allowed:rule.path("allowedChannels"))if(allowed.asText().equals(key))return true;
        return false;
    }
}
