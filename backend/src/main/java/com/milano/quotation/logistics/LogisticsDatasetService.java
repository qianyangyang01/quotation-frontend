package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class LogisticsDatasetService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final LogisticsDatasetGuard guard;
    private final AssetStorageService storage;

    public LogisticsDatasetService(JdbcClient jdbc, ObjectMapper mapper, LogisticsDatasetGuard guard, AssetStorageService storage) {
        this.jdbc=jdbc; this.mapper=mapper; this.guard=guard; this.storage=storage;
    }
    @Transactional(readOnly=true)
    public List<ObjectNode> list() {
        return jdbc.sql("select (to_jsonb(d))::text from logistics_dataset d order by created_at desc,id")
                .query((rs,n)->json(rs.getString(1))).list();
    }
    @Transactional
    public ObjectNode create(String name,String actor) {
        if(name==null || name.isBlank() || name.length()>120) throw AppException.unprocessable("请输入120字以内的新物流库名称");
        var id=UUID.randomUUID();
        jdbc.sql("insert into logistics_dataset(id,name,status,created_by) values(:id,:name,'preparing',:actor)")
                .param("id",id).param("name",name.trim()).param("actor",actor).update();
        return dataset(id);
    }
    public ObjectNode dataset(UUID id) {
        return jdbc.sql("select to_jsonb(d)::text from logistics_dataset d where id=:id").param("id",id)
                .query((rs,n)->json(rs.getString(1))).optional().orElseThrow(()->AppException.notFound("物流库不存在"));
    }
    @Transactional(readOnly=true)
    public ObjectNode workspace(UUID id) {
        var out=mapper.createObjectNode(); out.set("dataset",dataset(id));
        out.set("providers",array(jdbc.sql("select (payload || jsonb_build_object('id',id,'datasetId',dataset_id,'_version',version))::text from logistics_provider where dataset_id=:id order by payload->>'name'").param("id",id).query(String.class).list()));
        out.set("channels",channelViews(id));
        out.set("versions",array(jdbc.sql("""
                select ((v.payload - 'rows' - 'issues' - 'diffRows') || jsonb_build_object(
                'id',v.id,'channelId',v.channel_id,'status',v.status,'versionNumber',v.version_number,
                'rowCount',jsonb_array_length(coalesce(v.payload->'rows','[]'::jsonb)),
                'issueCount',jsonb_array_length(coalesce(v.payload->'issues','[]'::jsonb))))::text
                from logistics_version v join logistics_channel c on c.id=v.channel_id
                where c.dataset_id=:id order by v.created_at desc,v.id
                """).param("id",id).query(String.class).list()));
        return out;
    }
    ArrayNode channelViews(UUID id) {
        return array(jdbc.sql("""
                select (c.payload || jsonb_build_object('id',c.id,'datasetId',c.dataset_id,'ruleId',c.rule_id,
                'providerId',p.id,'providerName',p.payload->>'name','code',c.code,'currentVersionId',c.current_version_id,
                'archived',c.archived_at is not null,'_version',c.version,'updatedAt',c.updated_at,
                'quoteReady',c.current_version_id is not null and c.archived_at is null and coalesce((select (v.payload->>'quoteReady')::boolean from logistics_version v where v.id=c.current_version_id),true)
                 and coalesce((c.payload->>'enabled')::boolean,true) and coalesce((p.payload->>'enabled')::boolean,true),
                'channelKey',concat(c.rule_id,'::',p.payload->>'name','::',c.code)))::text
                from logistics_channel c join logistics_provider p on p.id=c.provider_id
                where c.dataset_id=:id order by p.payload->>'name',c.payload->>'name',c.id
                """).param("id",id).query(String.class).list());
    }
    @Transactional(readOnly=true)
    public com.milano.quotation.common.PageResponse<JsonNode> prices(UUID id,int page,int size,String query,String country,String attribute) {
        if(page<0||size<1||size>200)throw AppException.unprocessable("分页参数不合法");
        dataset(id);
        var from="""
                logistics_channel c join logistics_provider p on p.id=c.provider_id
                join logistics_version v on v.id=c.current_version_id
                cross join lateral jsonb_array_elements(v.payload->'rows') item
                where c.dataset_id=:dataset
                and (:query='' or position(lower(:query) in lower(concat(p.payload->>'name',c.payload->>'name')))>0)
                and (:country='' or lower(item->>'countryCode')=lower(:country) or item->>'areaName'=:country)
                and (:attribute='' or c.payload->>'logisticsAttribute'=:attribute)
                """;
        var params=new HashMap<String,Object>();params.put("dataset",id);params.put("query",query);params.put("search","%"+query.toLowerCase(Locale.ROOT)+"%");params.put("country",country);params.put("attribute",attribute);
        long total=jdbc.sql("select count(*) from "+from).params(params).query(Long.class).single();
        params.put("limit",size);params.put("offset",(long)page*size);
        var items=jdbc.sql("select (item || jsonb_build_object('providerName',p.payload->>'name','channelName',c.payload->>'name','channelId',c.id,'versionId',v.id,'versionNumber',v.version_number,'quoteReady',c.archived_at is null and coalesce((c.payload->>'enabled')::boolean,true) and coalesce((p.payload->>'enabled')::boolean,true) and coalesce((v.payload->>'quoteReady')::boolean,true),'logisticsAttribute',c.payload->>'logisticsAttribute'))::text from "+from+" order by p.payload->>'name',c.payload->>'name',item->>'countryCode',(item->>'weightFromKg')::numeric limit :limit offset :offset")
                .params(params).query((rs,n)->(JsonNode)json(rs.getString(1))).list();
        return new com.milano.quotation.common.PageResponse<>(items,page,size,total,(int)Math.ceil(total/(double)size));
    }
    @Transactional(readOnly=true)
    public ObjectNode preview(UUID target,ArrayNode selections) {
        var next=dataset(target);
        if(!next.path("status").asText().equals("preparing")) throw AppException.conflict("只有准备中的物流库可以初始化切换");
        var active=guard.activeId(); var old=channelViews(active); var fresh=channelViews(target);
        var chosen=new HashMap<String,String>();
        for(var selection:selections) {
            var from=selection.path("oldChannelId").asText();
            if(chosen.put(from,selection.path("newChannelId").asText())!=null) throw AppException.unprocessable("旧渠道映射重复");
        }
        var result=mapper.createObjectNode().put("sourceDatasetId",active.toString()).put("targetDatasetId",target.toString());
        var mappings=result.putArray("mappings"); var seen=new HashSet<String>(); var usedTargets=new HashSet<String>();
        for(var source:old) {
            var candidates=new ArrayList<JsonNode>();
            for(var dest:fresh) if(dest.path("quoteReady").asBoolean() && matchIdentity(source).equals(matchIdentity(dest))) candidates.add(dest);
            var selected=chosen.getOrDefault(source.path("id").asText(),candidates.size()==1?candidates.getFirst().path("id").asText():"");
            JsonNode destination=null;
            for(var dest:fresh) if(dest.path("id").asText().equals(selected)) destination=dest;
            if(!selected.isBlank() && (destination==null || !destination.path("quoteReady").asBoolean())) throw AppException.unprocessable("映射目标必须是新库已审核可用渠道");
            if(!selected.isBlank() && !usedTargets.add(selected)) throw AppException.unprocessable("多个旧渠道不能自动合并到同一个新渠道，请先核对映射");
            var item=mappings.addObject().put("oldChannelId",source.path("id").asText()).put("oldChannelKey",source.path("channelKey").asText())
                    .put("oldName",source.path("providerName").asText()+" / "+source.path("name").asText()).put("newChannelId",selected)
                    .put("status",selected.isBlank()?(candidates.size()>1?"ambiguous":"unmatched"):"matched");
            item.set("candidates",mapper.valueToTree(candidates)); if(destination!=null) item.set("target",destination);
            seen.add(source.path("id").asText());
        }
        if(!seen.containsAll(chosen.keySet())) throw AppException.unprocessable("映射中包含不属于当前旧库的渠道");
        var pending=result.putArray("pendingChannels"); int ready=0;
        for(var c:fresh) { if(c.path("quoteReady").asBoolean()) ready++; else pending.add(c); }
        int unresolved=0; for(var m:mappings) if(!m.path("status").asText().equals("matched")) unresolved++;
        result.put("readyChannels",ready).put("unmappedChannels",unresolved);
        var changes=result.putArray("bindingChanges");var byKey=new HashMap<String,JsonNode>();
        for(var m:mappings)if(m.path("status").asText().equals("matched"))byKey.put(m.path("oldChannelKey").asText(),m.path("target"));
        var linked=bindings();
        for(var f:linked.path("finance"))bindingChanges(f.path("payload"),"finance","channel-policies","",byKey,changes);
        for(var t:linked.path("templates"))bindingChanges(t.path("payload"),"template",t.path("id").asText(),"",byKey,changes);
        result.put("draftsToReprice",jdbc.sql("select count(*) from quotation_draft").query(Long.class).single());
        result.put("sourceFingerprint",fingerprint(active));
        result.put("targetFingerprint",fingerprint(target));
        result.put("previewToken",hash(result.toString()));
        return result;
    }
    @Transactional
    public ObjectNode backup(UUID target,String actor) {
        if(!dataset(target).path("status").asText().equals("preparing")) throw AppException.conflict("新库不在准备状态");
        // Lock writers while capturing a recoverable, coherent source snapshot.
        lockCutover(); var source=guard.activeId(); var before=fingerprint(source);
        var snapshot=workspace(source); snapshot.set("bindings",bindings());
        snapshot.set("fullVersions",array(jdbc.sql("select to_jsonb(v)::text from logistics_version v join logistics_channel c on c.id=v.channel_id where c.dataset_id=:id order by v.id").param("id",source).query(String.class).list()));
        snapshot.set("drafts",array(jdbc.sql("select to_jsonb(d)::text from quotation_draft d order by owner_account").query(String.class).list()));
        var bytes=snapshot.toString().getBytes(StandardCharsets.UTF_8);
        var key="logistics/backups/"+target+"/"+UUID.randomUUID()+".json";
        storage.putRaw(key,new ByteArrayInputStream(bytes),bytes.length,"application/json");
        try(var saved=storage.openRaw(key)){if(!AssetStorageService.sha256(saved.readAllBytes()).equals(AssetStorageService.sha256(bytes)))throw AppException.conflict("备份回读校验失败");}catch(java.io.IOException e){throw AppException.conflict("备份回读失败，请重试");}
        var backup=mapper.createObjectNode().put("objectKey",key).put("sha256",AssetStorageService.sha256(bytes))
                .put("sourceDatasetId",source.toString()).put("sourceFingerprint",before).put("createdAt",Instant.now().toString()).put("createdBy",actor);
        jdbc.sql("update logistics_dataset set payload=jsonb_set(payload,'{backup}',cast(:backup as jsonb)) where id=:id")
                .param("backup",backup.toString()).param("id",target).update();
        return backup;
    }
    @Transactional
    public ObjectNode activate(UUID target,ObjectNode input,String actor) {
        if(!input.path("reviewConfirmed").asBoolean() || input.path("note").asText().isBlank()) throw AppException.unprocessable("必须确认切换清单并填写审核备注");
        lockCutover();
        var mappings=input.path("mappings"); if(!mappings.isArray()) throw AppException.unprocessable("缺少核对后的映射清单");
        var preview=preview(target,(ArrayNode)mappings);
        if(!preview.path("previewToken").asText().equals(input.path("previewToken").asText())) throw AppException.conflict("数据或关联已变化，请重新预览并核对");
        if(preview.path("readyChannels").asInt()<1) throw AppException.unprocessable("至少审核发布一个可报价的新渠道后才能切换");
        if((preview.path("unmappedChannels").asInt()>0 || !preview.path("pendingChannels").isEmpty()) && !input.path("unavailableConfirmed").asBoolean()) throw AppException.unprocessable("请明确确认未映射和暂不可用渠道");
        var backup=dataset(target).path("payload").path("backup");
        if(!backup.path("sourceFingerprint").asText().equals(preview.path("sourceFingerprint").asText())) throw AppException.conflict("缺少当前旧库备份，或备份后数据已变化，请重新备份");
        var source=UUID.fromString(preview.path("sourceDatasetId").asText());
        var mappingIndex=new HashMap<String,JsonNode>();
        for(var mapping:preview.path("mappings")) if(mapping.has("target")) mappingIndex.put(mapping.path("oldChannelKey").asText(),mapping.path("target"));
        migrateBindings(mappingIndex);
        jdbc.sql("""
                update logistics_version v set status='rejected',payload=v.payload || jsonb_build_object('status','rejected','auditNote','物流库整体归档终止待审稿','rejectedBy',:actor)
                from logistics_channel c where c.id=v.channel_id and c.dataset_id=:source and v.status='draft'
                """).param("source",source).param("actor",actor).update();
        jdbc.sql("update logistics_channel set archived_at=coalesce(archived_at,now()),archived_by=:actor,archive_reason='物流库整体重建归档',version=version+1,updated_at=now() where dataset_id=:source")
                .param("source",source).param("actor",actor).update();
        jdbc.sql("update logistics_dataset set status='archived',revision=revision+1 where id=:id").param("id",source).update();
        preview.put("activatedBy",actor).put("activatedAt",Instant.now().toString()).put("note",input.path("note").asText());
        jdbc.sql("update logistics_dataset set status='active',revision=revision+1,activated_at=now(),payload=jsonb_set(payload,'{activation}',cast(:result as jsonb)) where id=:id")
                .param("id",target).param("result",preview.toString()).update();
        return preview;
    }
    private void lockCutover() {
        // Same lock order for backup and activation. Table locks cover phantom template writes.
        jdbc.sql("select id from logistics_dataset order by id for update").query(UUID.class).list();
        jdbc.sql("lock table finance_setting,quotation_template,quotation_draft in share row exclusive mode").update();
    }
    private static void bindingChanges(JsonNode node,String kind,String id,String path,Map<String,JsonNode> mapping,ArrayNode changes) {
        if(node.isObject())for(var field:node.properties()) {
            var child=field.getValue();var p=path+"/"+field.getKey();
            if(field.getKey().equals("channelKey")&&child.isTextual())bindingChange(child.asText(),kind,id,p,mapping,changes);
            else if(field.getKey().equals("allowedChannels")&&child.isArray())for(int i=0;i<child.size();i++)bindingChange(child.get(i).asText(),kind,id,p+"/"+i,mapping,changes);
            else bindingChanges(child,kind,id,p,mapping,changes);
        }else if(node.isArray())for(int i=0;i<node.size();i++)bindingChanges(node.get(i),kind,id,path+"/"+i,mapping,changes);
    }
    private static void bindingChange(String before,String kind,String id,String path,Map<String,JsonNode> mapping,ArrayNode changes) {
        if(before.isBlank())return;var target=mapping.get(before);
        changes.addObject().put("kind",kind).put("id",id).put("path",path).put("before",before)
                .put("after",target==null?before:target.path("channelKey").asText()).put("status",target==null?"unresolved":"mapped");
    }
    private ObjectNode bindings() {
        var result=mapper.createObjectNode();
        result.set("finance",array(jdbc.sql("select to_jsonb(f)::text from finance_setting f where setting_key='channel-policies'").query(String.class).list()));
        result.set("templates",array(jdbc.sql("select to_jsonb(t)::text from quotation_template t order by id").query(String.class).list()));
        return result;
    }
    private String fingerprint(UUID dataset) {
        return hash(workspace(dataset).toString()+jdbc.sql("select coalesce(string_agg(v.id::text || ':' || v.payload::text,'|' order by v.id),'') from logistics_version v join logistics_channel c on c.id=v.channel_id where c.dataset_id=:id").param("id",dataset).query(String.class).single()+bindings());
    }
    private void migrateBindings(Map<String,JsonNode> mapping) {
        var finance=jdbc.sql("select payload::text from finance_setting where setting_key='channel-policies'").query(String.class).optional();
        if(finance.isPresent()) {
            var value=mapper.readTree(finance.get()); replaceAllowedChannels(value,mapping);
            jdbc.sql("update finance_setting set payload=cast(:payload as jsonb),version=version+1,updated_at=now() where setting_key='channel-policies'").param("payload",value.toString()).update();
        }
        for(var template:bindings().path("templates")) {
            var payload=template.path("payload").deepCopy(); boolean changed=migrateSelections(payload,mapping);
            if(changed) jdbc.sql("update quotation_template set payload=cast(:payload as jsonb),version=version+1,updated_at=now() where id=:id")
                    .param("id",UUID.fromString(template.path("id").asText())).param("payload",payload.toString()).update();
        }
    }
    static void replaceAllowedChannels(JsonNode node,Map<String,JsonNode> mapping) {
        if(node.isObject()) for(var entry:node.properties()) {
            if(entry.getKey().equals("allowedChannels") && entry.getValue().isArray()) {
                var values=(ArrayNode)entry.getValue();
                for(int i=0;i<values.size();i++) { var target=mapping.get(values.get(i).asText()); if(target!=null) values.set(i,target.path("channelKey")); }
            } else replaceAllowedChannels(entry.getValue(),mapping);
        } else if(node.isArray()) node.forEach(child->replaceAllowedChannels(child,mapping));
    }
    static boolean migrateSelections(JsonNode node,Map<String,JsonNode> mapping) {
        boolean changed=false;
        if(node.isObject()) {
            var target=mapping.get(node.path("channelKey").asText());
            if(target!=null) {
                var obj=(ObjectNode)node;
                obj.set("channelKey",target.path("channelKey")); obj.set("ruleId",target.path("ruleId")); obj.set("rule",target.path("name"));
                obj.set("carrier",target.path("providerName")); obj.set("transport",target.path("name")); obj.set("channelCode",target.path("code")); changed=true;
            }
            for(var entry:node.properties()) if(entry.getValue().isObject() || entry.getValue().isArray()) changed=migrateSelections(entry.getValue(),mapping)||changed;
        } else if(node.isArray()) for(var child:node) changed=migrateSelections(child,mapping)||changed;
        return changed;
    }
    static String matchIdentity(JsonNode channel) {
        return normalize(channel.path("providerName").asText())+"|"+normalize(channel.path("name").asText())+"|"+normalize(channel.path("logisticsAttribute").asText("普货"));
    }
    static String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_（）()]","").replace("4px","递四方"); }
    static String hash(String text) { return AssetStorageService.sha256(text.getBytes(StandardCharsets.UTF_8)); }
    ObjectNode json(String text) { return (ObjectNode)mapper.readTree(text); }
    ArrayNode array(List<String> rows) { var result=mapper.createArrayNode(); rows.forEach(value->result.add(mapper.readTree(value))); return result; }
}
