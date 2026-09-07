package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import java.util.*;

@Service
public class CompanyChannelService {
    private final JdbcClient jdbc;private final ObjectMapper mapper;
    public CompanyChannelService(JdbcClient jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    @Transactional(readOnly=true)
    public ObjectNode snapshot(){
        var state=state(false);
        var result=mapper.createObjectNode().put("revision",state.path("revision").asLong()).put("enabled",state.path("enabled").asBoolean());
        var entries=result.putArray("entries");jdbc.sql("select payload::text from logistics_company_channel order by payload->>'providerName',payload->>'channelName',id")
                .query((rs,n)->mapper.readTree(rs.getString(1))).list().forEach(entries::add);return result;
    }
    public ObjectNode state(boolean lock){return jdbc.sql("select to_jsonb(s)::text from logistics_company_state s where singleton"+(lock?" for update":""))
            .query((rs,n)->(ObjectNode)mapper.readTree(rs.getString(1))).single();}
    public UUID importDataset(){var s=state(false);return s.path("paused").asBoolean()?UUID.fromString(s.path("target_dataset_id").asText()):jdbc.sql("select id from logistics_dataset where status='active'").query(UUID.class).single();}
    public ObjectNode scopeFor(UUID provider,UUID channel){
        var result=snapshot();if(provider==null&&channel==null)return result;
        var entries=result.path("entries").deepCopy();var selected=result.putArray("entries");
        String name=provider==null?"":jdbc.sql("select payload->>'name' from logistics_provider where id=:id").param("id",provider).query(String.class).single();
        String company=channel==null?"":jdbc.sql("select company_channel_id::text from logistics_company_binding where channel_id=:id").param("id",channel).query(String.class).optional().orElse("");
        for(var e:entries)if((provider==null||CompanyChannelScope.normalize(name).equals(CompanyChannelScope.normalize(e.path("providerName").asText())))&&(channel==null||company.equals(e.path("id").asText())))selected.add(e);
        return result;
    }
    public ObjectNode list(){var result=snapshot();result.set("state",state(false));return result;}
    @Transactional
    public ObjectNode save(ObjectNode input,String actor){
        var state=state(true);
        if(state.path("paused").asBoolean())throw AppException.conflict("重建期间公司清单已锁定");
        if(!input.path("revision").isIntegralNumber()||input.path("revision").asLong()!=state.path("revision").asLong())throw AppException.conflict("公司渠道清单已变化，请刷新");
        var entries=input.path("entries");if(!entries.isArray()||entries.isEmpty()||entries.size()>10000)throw AppException.unprocessable("请提供有效公司渠道清单");
        validate(entries);
        var existing=new HashSet<String>();jdbc.sql("select id::text from logistics_company_channel").query(String.class).list().forEach(existing::add);
        var ids=new HashSet<String>();
        for(var entry:entries){
            var e=(ObjectNode)entry.deepCopy();var id=e.path("id").asText();if(id.isBlank())id=UUID.randomUUID().toString();
            UUID parsed;try{parsed=UUID.fromString(id);}catch(IllegalArgumentException bad){throw AppException.unprocessable("公司渠道标识无效");}
            var priorProvider=jdbc.sql("select payload->>'providerName' from logistics_company_channel where id=:id").param("id",parsed).query(String.class).optional();
            if(priorProvider.isPresent()&&!priorProvider.get().equals(e.path("providerName").asText()))throw AppException.unprocessable("已有公司渠道不能改绑其他物流商；物流商别称请登记别名");
            if(!ids.add(id))throw AppException.unprocessable("公司渠道标识重复");
            e.put("id",id);if(!e.has("enabled"))e.put("enabled",true);
            jdbc.sql("insert into logistics_company_channel(id,enabled,payload,updated_by) values(:id,:enabled,cast(:p as jsonb),:actor) on conflict(id) do update set enabled=excluded.enabled,payload=excluded.payload,updated_by=excluded.updated_by,updated_at=now()")
                    .param("id",parsed).param("enabled",e.path("enabled").asBoolean()).param("p",e.toString()).param("actor",actor).update();
        }
        if(!ids.containsAll(existing))throw AppException.unprocessable("已有公司渠道只能停用，不能从清单删除");
        jdbc.sql("update logistics_company_state set revision=revision+1,enabled=true where singleton").update();
        var result=snapshot();jdbc.sql("insert into logistics_company_revision(revision,payload,created_by) values(:r,cast(:p as jsonb),:actor)")
                .param("r",result.path("revision").asLong()).param("p",result.toString()).param("actor",actor).update();
        return list();
    }
    static void validate(JsonNode entries){
        var names=new HashMap<String,String>();var codes=new HashMap<String,String>();var providers=new HashMap<String,String>();
        int index=0;for(var e:entries){var owner="entry-"+(index++);
            for(var field:List.of("providerName","channelName","logisticsAttribute"))if(e.path(field).asText().isBlank()||e.path(field).asText().length()>180)throw AppException.unprocessable("物流商、渠道名称及货物属性不能为空或过长");
            var provider=CompanyChannelScope.normalize(e.path("providerName").asText());
            var labels=new ArrayList<String>();labels.add(e.path("channelName").asText());
            for(var field:List.of("aliases","providerAliases","productCodes")){
                if(e.has(field)&&!e.path(field).isArray())throw AppException.unprocessable("别名及产品代码必须是数组");
                for(var v:e.path(field))if(!v.isTextual()||v.asText().isBlank()||v.asText().length()>180)throw AppException.unprocessable("别名及代码无效");
            }
            claim(providers,provider,provider);for(var alias:e.path("providerAliases"))claim(providers,CompanyChannelScope.normalize(alias.asText()),provider);
            for(var a:e.path("aliases"))labels.add(a.asText());
            for(var name:labels)claim(names,provider+"|"+CompanyChannelScope.normalize(name),owner);
            for(var code:e.path("productCodes"))claim(codes,provider+"|"+CompanyChannelScope.normalize(code.asText()),owner);
        }
    }
    private static void claim(Map<String,String> map,String key,String owner){var prior=map.putIfAbsent(key,owner);if(prior!=null&&!prior.equals(owner))throw AppException.unprocessable("同一物流商的名称、别名或产品代码重复："+key);}
    public void bind(UUID dataset,UUID channel,String companyId){
        if(companyId==null||companyId.isBlank())return;
        jdbc.sql("insert into logistics_company_binding(channel_id,company_channel_id,dataset_id) values(:c,:id,:d) on conflict(channel_id) do update set company_channel_id=excluded.company_channel_id")
                .param("c",channel).param("id",UUID.fromString(companyId)).param("d",dataset).update();
    }
    public void assertImport(UUID dataset){
        var s=jdbc.sql("select to_jsonb(s)::text from logistics_company_state s where singleton for share").query((rs,n)->(ObjectNode)mapper.readTree(rs.getString(1))).single();if(s.path("paused").asBoolean()&&!dataset.toString().equals(s.path("target_dataset_id").asText()))throw AppException.conflict("物流重建中，旧价格暂停写入");
        if(s.path("paused").asBoolean()&&!jdbc.sql("select phase from logistics_company_rebuild where id=:id").param("id",UUID.fromString(s.path("rebuild_id").asText())).query(String.class).single().equals("deleted"))throw AppException.conflict("请先完成旧价格备份和物理清理，再导入基准");
    }
    public void assertChannel(UUID channel){if(!jdbc.sql("select logistics_company_allowed(:id)").param("id",channel).query(Boolean.class).single())throw AppException.conflict("该渠道不在公司允许范围或已停用");}
}
