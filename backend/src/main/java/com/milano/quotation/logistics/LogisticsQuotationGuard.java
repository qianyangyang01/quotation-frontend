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
        if(!queries.manifest().revision().equals(quotation.path("logisticsRevision").asText()))throw AppException.conflict("物流版本已更新或缺少版本信息，请重新加载并计价后提交");
        var policies=mapper.readTree(jdbc.sql("select payload::text from finance_setting where setting_key='channel-policies' for share").query(String.class).optional().orElse("[]"));
        var channels=jdbc.sql("""
            select jsonb_build_object('key',concat(c.rule_id,'::',p.payload->>'name','::',c.code),
                'versionId',v.id,'channelId',c.id,'rows',v.payload->'rows')::text
            from logistics_channel c join logistics_provider p on p.id=c.provider_id
            join logistics_version v on v.id=c.current_version_id and v.status='published'
            where c.dataset_id=:id and c.archived_at is null
            and coalesce((c.payload->>'enabled')::boolean,true) and coalesce((p.payload->>'enabled')::boolean,true)
            and coalesce((v.payload->>'quoteReady')::boolean,true)
            """).param("id",dataset).query((rs,n)->mapper.readTree(rs.getString(1))).list();
        for(var option:quotation.path("quoteOptions")) {
            var key=option.path("channelKey").asText();var country=option.path("country").asText();
            var channel=channels.stream().filter(c->c.path("key").asText().equals(key)).findFirst().orElseThrow(()->AppException.conflict("报价渠道已归档、未适配或不存在，请重新选择"));
            boolean countryAvailable=false;
            for(var row:channel.path("rows"))if((row.path("areaName").asText().equals(country)||row.path("countryCode").asText().equalsIgnoreCase(country))&&row.path("quoteReady").asBoolean(true))countryAvailable=true;
            if(!countryAvailable||!allowed(policies,quotation.path("logisticsAttribute").asText(),country,key))throw AppException.unprocessable("渠道不在该国家及货物属性的财务允许范围内");
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
