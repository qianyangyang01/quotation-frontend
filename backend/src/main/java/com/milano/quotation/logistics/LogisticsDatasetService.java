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
    @Transactional(readOnly=true)
    public List<ObjectNode> preparingRequiredPreviews() {
        return jdbc.sql("""
                select jsonb_build_object('id',d.id,'name',d.name,'status',d.status,'createdAt',d.created_at,
                    'revision',coalesce((r.payload->>'revision')::bigint,0),
                    'confirmed',coalesce((r.payload->>'confirmed')::boolean,false),
                    'requiredCount',jsonb_array_length(coalesce(r.payload->'channelIds','[]'::jsonb)),
                    'confirmedBy',coalesce(r.payload->>'confirmedBy',''),'confirmedAt',coalesce(r.payload->>'confirmedAt',''))::text
                from logistics_dataset d
                left join lateral (select payload from logistics_required_revision where dataset_id=d.id order by revision desc limit 1) r on true
                where d.status='preparing'
                order by coalesce((r.payload->>'confirmed')::boolean,false) desc,d.created_at desc,d.id
                """).query((rs,n)->json(rs.getString(1))).list();
    }
    @Transactional(readOnly=true)
    public ObjectNode requiredChannelPreview(UUID id) {
        var dataset=dataset(id);
        if(!dataset.path("status").asText().equals("preparing"))throw AppException.conflict("财务只预览新库准备区的必用渠道");
        var required=jdbc.sql("select payload::text from logistics_required_revision where dataset_id=:id order by revision desc limit 1").param("id",id)
                .query((rs,n)->json(rs.getString(1))).optional().orElseGet(()->mapper.createObjectNode().put("revision",0).put("confirmed",false));
        if(!required.has("channelIds"))required.putArray("channelIds");
        var selected=new LinkedHashSet<String>();for(var channel:required.path("channelIds"))selected.add(channel.asText());
        var result=mapper.createObjectNode().put("datasetId",id.toString()).put("datasetName",dataset.path("name").asText())
                .put("status",dataset.path("status").asText()).put("revision",required.path("revision").asLong())
                .put("confirmed",required.path("confirmed").asBoolean()).put("note",required.path("note").asText())
                .put("confirmedBy",required.path("confirmedBy").asText()).put("confirmedAt",required.path("confirmedAt").asText());
        var channels=result.putArray("channels");int ready=0;
        for(var c:channelViews(id))if(selected.contains(c.path("id").asText())){
            var entry=(ObjectNode)c.deepCopy();
            var latest=jdbc.sql("select payload::text from logistics_version where channel_id=:id order by case when status='published' then 0 else 1 end,created_at desc limit 1")
                    .param("id",UUID.fromString(c.path("id").asText())).query((rs,n)->json(rs.getString(1))).optional();
            var countries=new TreeSet<String>();var zones=new TreeSet<String>();var reasons=new TreeSet<String>();int count=0;
            if(latest.isPresent())for(var price:latest.get().path("rows")){count++;countries.add(price.path("areaName").asText());if(!price.path("zoneName").asText().isBlank())zones.add(price.path("countryCode").asText()+" / "+price.path("zoneName").asText());if(!price.path("pendingReason").asText().isBlank())reasons.add(price.path("pendingReason").asText());}
            entry.put("priceRows",count);entry.set("countries",mapper.valueToTree(countries));entry.set("zones",mapper.valueToTree(zones));entry.set("pendingReasons",mapper.valueToTree(reasons));channels.add(entry);
            if(entry.path("quoteReady").asBoolean())ready++;
        }
        result.put("requiredCount",channels.size()).put("readyCount",ready);
        return result;
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
    public ObjectNode requiredChannels(UUID id) {
        dataset(id);
        var result=jdbc.sql("select payload::text from logistics_required_revision where dataset_id=:id order by revision desc limit 1").param("id",id)
                .query((rs,n)->json(rs.getString(1))).optional().orElseGet(()->mapper.createObjectNode().put("revision",0).put("confirmed",false));
        if(!result.has("channelIds"))result.putArray("channelIds");
        var rows=result.putArray("channels");
        for(var c:channelViews(id)){
            var entry=(ObjectNode)c.deepCopy();
            var latest=jdbc.sql("select payload::text from logistics_version where channel_id=:id order by case when status='published' then 0 else 1 end,created_at desc limit 1")
                    .param("id",UUID.fromString(c.path("id").asText())).query((rs,n)->json(rs.getString(1))).optional();
            var countries=new TreeSet<String>();var zones=new TreeSet<String>();var reasons=new TreeSet<String>();int count=0;
            if(latest.isPresent())for(var price:latest.get().path("rows")){count++;countries.add(price.path("areaName").asText());if(!price.path("zoneName").asText().isBlank())zones.add(price.path("countryCode").asText()+" / "+price.path("zoneName").asText());if(!price.path("pendingReason").asText().isBlank())reasons.add(price.path("pendingReason").asText());}
            entry.put("priceRows",count);entry.set("countries",mapper.valueToTree(countries));entry.set("zones",mapper.valueToTree(zones));entry.set("pendingReasons",mapper.valueToTree(reasons));rows.add(entry);
        }
        return result;
    }
    @Transactional
    public ObjectNode saveRequiredChannels(UUID id,ObjectNode input,String actor) {
        jdbc.sql("select id from logistics_dataset where id=:id for update").param("id",id).query(UUID.class).optional().orElseThrow(()->AppException.notFound("物流库不存在"));
        if(!dataset(id).path("status").asText().equals("preparing"))throw AppException.conflict("仅准备区可修改上线必用清单");
        var current=requiredChannels(id);if(!input.path("revision").isIntegralNumber()||input.path("revision").asLong()!=current.path("revision").asLong())throw AppException.conflict("必用清单已变化，请重新加载");
        if(!input.path("channelIds").isArray()||input.path("note").asText().isBlank())throw AppException.unprocessable("请选择渠道并填写核对备注");
        var valid=new HashSet<String>();for(var c:current.path("channels"))if(!c.path("archived").asBoolean())valid.add(c.path("id").asText());
        var chosen=new LinkedHashSet<String>();for(var c:input.path("channelIds"))if(!valid.contains(c.asText())||!chosen.add(c.asText()))throw AppException.unprocessable("必用清单含无效、已归档或重复渠道");
        if(input.path("confirmed").asBoolean()&&chosen.isEmpty())throw AppException.unprocessable("确认的必用清单不能为空");
        var result=mapper.createObjectNode().put("revision",current.path("revision").asLong()+1).put("confirmed",input.path("confirmed").asBoolean()).put("note",input.path("note").asText()).put("confirmedBy",actor).put("confirmedAt",Instant.now().toString());result.set("channelIds",mapper.valueToTree(chosen));
        jdbc.sql("insert into logistics_required_revision(dataset_id,revision,payload,created_by) values(:id,:revision,cast(:payload as jsonb),:actor)").param("id",id).param("revision",result.path("revision").asLong()).param("payload",result.toString()).param("actor",actor).update();
        return requiredChannels(id);
    }
    @Transactional(readOnly=true)
    public ObjectNode workspace(UUID id) {
        var out=mapper.createObjectNode(); out.set("dataset",dataset(id));
        out.set("providers",array(jdbc.sql("select (payload || jsonb_build_object('id',id,'datasetId',dataset_id,'_version',version))::text from logistics_provider where dataset_id=:id order by payload->>'name'").param("id",id).query(String.class).list()));
        out.set("channels",channelViews(id));
        out.set("versions",array(jdbc.sql("""
                select (v.workspace_payload || jsonb_build_object(
                'id',v.id,'channelId',v.channel_id,'status',v.status,'versionNumber',v.version_number,'quoteReady',logistics_version_quote_ready(v.id),
                'rowCount',v.row_count,'issueCount',v.issue_count))::text
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
                'quoteReady',c.current_version_id is not null and c.archived_at is null and logistics_version_quote_ready(c.current_version_id)
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
        // The materialized page/count query briefly holds the expanded current rows twice. Keep this
        // transaction-local so production-wide memory settings and unrelated requests are unaffected.
        jdbc.sql("set local work_mem='96MB'").update();
        var result=jdbc.sql("""
                with channel_base as materialized (
                  select c.id channel_id,c.payload channel_payload,c.archived_at,p.payload provider_payload,
                         v.id version_id,v.version_number,v.payload version_payload,
                         c.archived_at is null and coalesce((c.payload->>'enabled')::boolean,true)
                           and coalesce((p.payload->>'enabled')::boolean,true)
                           and logistics_version_quote_ready(v.id) quote_ready
                  from logistics_channel c join logistics_provider p on p.id=c.provider_id
                  join logistics_version v on v.id=c.current_version_id
                  where c.dataset_id=:dataset
                ), filtered as materialized (
                  select item,channel_id,version_id,version_number,quote_ready,
                         provider_payload->>'name' provider_name,channel_payload->>'name' channel_name,
                         channel_payload->>'logisticsAttribute' logistics_attribute
                  from channel_base cross join lateral jsonb_array_elements(version_payload->'rows') item
                  where (:query='' or position(lower(:query) in lower(concat(provider_payload->>'name',channel_payload->>'name')))>0)
                    and (:country='' or lower(item->>'countryCode')=lower(:country) or item->>'areaName'=:country)
                    and (:attribute='' or channel_payload->>'logisticsAttribute'=:attribute)
                ), stats as (select count(*) total from filtered), page_rows as (
                  select (item || jsonb_build_object('providerName',provider_name,'channelName',channel_name,
                           'channelId',channel_id,'versionId',version_id,'versionNumber',version_number,
                           'quoteReady',quote_ready,'logisticsAttribute',logistics_attribute)) payload,
                         provider_name,channel_name,item->>'countryCode' country_code,(item->>'weightFromKg')::numeric weight_from
                  from filtered order by provider_name,channel_name,item->>'countryCode',(item->>'weightFromKg')::numeric
                  limit :limit offset :offset
                )
                select jsonb_build_object('total',stats.total,'items',coalesce(
                  jsonb_agg(page_rows.payload order by page_rows.provider_name,page_rows.channel_name,page_rows.country_code,page_rows.weight_from)
                    filter(where page_rows.payload is not null),'[]'::jsonb))::text
                from stats left join page_rows on true group by stats.total
                """).param("dataset",id).param("query",query).param("country",country).param("attribute",attribute)
                .param("limit",size).param("offset",(long)page*size).query(String.class).single();
        var payload=json(result);long total=payload.path("total").asLong();var items=new ArrayList<JsonNode>();payload.path("items").forEach(items::add);
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
        var required=requiredChannels(target);var notReady=result.putArray("requiredNotReady");
        for(var requiredId:required.path("channelIds")){
            JsonNode found=null;for(var c:required.path("channels"))if(c.path("id").asText().equals(requiredId.asText()))found=c;
            if(found==null||!found.path("quoteReady").asBoolean())notReady.add(found==null?mapper.createObjectNode().put("id",requiredId.asText()).put("name","渠道已不可用"):found);
        }
        result.put("requiredRevision",required.path("revision").asLong()).put("requiredConfirmed",required.path("confirmed").asBoolean()).put("requiredCount",required.path("channelIds").size());
        result.put("requiredReady",required.path("confirmed").asBoolean()&&!required.path("channelIds").isEmpty()&&notReady.isEmpty());
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
        snapshot.set("requiredRevisions",array(jdbc.sql("select to_jsonb(r)::text from logistics_required_revision r where dataset_id=:id order by revision").param("id",source).query(String.class).list()));
        snapshot.set("billingAcceptances",array(jdbc.sql("select to_jsonb(a)::text from logistics_billing_acceptance a join logistics_version v on v.id=a.version_id join logistics_channel c on c.id=v.channel_id where c.dataset_id=:id order by a.id").param("id",source).query(String.class).list()));
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
        if(!preview.path("requiredReady").asBoolean()) throw AppException.unprocessable("必须先确认必用清单，并使全部必用渠道通过计费验收后才能切换");
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
