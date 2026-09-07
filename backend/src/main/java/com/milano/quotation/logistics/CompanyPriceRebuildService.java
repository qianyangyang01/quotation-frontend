package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;
import java.io.*;
import java.util.*;

/** Explicit maintenance workflow. No startup hook and no automatic publication or recovery. */
@Service
public class CompanyPriceRebuildService {
    private final JdbcClient jdbc;private final ObjectMapper mapper;private final CompanyChannelService directory;
    private final LogisticsDatasetService datasets;private final AssetStorageService storage;
    public CompanyPriceRebuildService(JdbcClient jdbc,ObjectMapper mapper,CompanyChannelService directory,LogisticsDatasetService datasets,AssetStorageService storage){
        this.jdbc=jdbc;this.mapper=mapper;this.directory=directory;this.datasets=datasets;this.storage=storage;
    }
    public ObjectNode baseline(){try(var in=new ClassPathResource("company-channel-baseline.json").getInputStream()){return (ObjectNode)mapper.readTree(in.readAllBytes());}catch(IOException e){throw AppException.conflict("公司渠道基准资源不可用");}}
    @Transactional(readOnly=true)
    public ObjectNode preview(){
        var result=mapper.createObjectNode();result.set("state",directory.state(false));
        result.put("priceVersions",count("logistics_version")).put("channels",count("logistics_channel")).put("quotations",count("quotation_record"));
        result.put("imports",count("logistics_import_batch"));result.put("historyFingerprint",historyFingerprint());
        result.put("priceFingerprint",priceFingerprint());result.put("previewToken",LogisticsDatasetService.hash(result.toString()));
        return result;
    }
    @Transactional
    public ObjectNode begin(ObjectNode input,String actor){
        var state=directory.state(true);
        if(state.path("paused").asBoolean())return job(UUID.fromString(state.path("rebuild_id").asText()));
        requireNote(input);
        jdbc.sql("lock table logistics_dataset,logistics_import_batch,quotation_record,quotation_draft,quotation_template,finance_setting in share row exclusive mode").update();
        var preview=preview();if(!preview.path("previewToken").asText().equals(input.path("previewToken").asText()))throw AppException.conflict("清理范围已变化，请重新预览");
        var id=UUID.randomUUID();var payload=preview.deepCopy().put("note",input.path("note").asText());
        payload.set("previousDirectory",directory.snapshot());
        var source=payload.putArray("sourceDatasets");jdbc.sql("select id::text from logistics_dataset order by id").query(String.class).list().forEach(source::add);
        var frozen=baseline();frozen.put("revision",state.path("revision").asLong());
        // Reuse prior stable IDs when re-running a rebuild; additions remain explicit.
        var prior=directory.snapshot();if(!prior.path("entries").isEmpty()){
            var existing=new LinkedHashMap<String,JsonNode>();for(var e:prior.path("entries"))existing.put(e.path("id").asText(),e);
            for(var e:frozen.path("entries"))existing.putIfAbsent(e.path("id").asText(),e);
            var list=frozen.putArray("entries");existing.values().forEach(list::add);
        }
        directory.save(frozen,actor);
        var scope=new CompanyChannelScope(directory.snapshot());var mappings=payload.putArray("channelMappings");
        for(var c:oldChannels()){
            var match=scope.match(c.path("providerName").asText(),c.path("channelName").asText(),"");
            var mapping=(ObjectNode)c.deepCopy();mapping.put("status",match.status()).put("reason",match.reason());
            if(match.accepted())mapping.put("companyChannelId",match.entry().path("id").asText());mappings.add(mapping);
        }
        var target=datasets.create("公司渠道 8.27 基准库",actor);payload.put("targetDatasetId",target.path("id").asText());
        jdbc.sql("insert into logistics_company_rebuild(id,phase,payload,created_by) values(:id,'paused',cast(:p as jsonb),:actor)")
                .param("id",id).param("p",payload.toString()).param("actor",actor).update();
        jdbc.sql("update logistics_company_state set paused=true,rebuild_id=:id,target_dataset_id=:target,revision=revision+1 where singleton")
                .param("id",id).param("target",UUID.fromString(target.path("id").asText())).update();
        jdbc.sql("update logistics_import_batch set status='interrupted',phase='rebuild-paused',lease_id=null,updated_at=now() where status in ('queued','processing')").update();
        return job(id);
    }
    @Transactional
    public ObjectNode backup(UUID id){
        var job=locked(id);if(Set.of("backed-up","deleted","completed").contains(job.path("phase").asText()))return job;
        if(!job.path("phase").asText().equals("paused"))throw AppException.conflict("重建状态不允许备份");
        jdbc.sql("lock table quotation_record,quotation_draft,quotation_template,finance_setting in share row exclusive mode").update();
        var snapshot=mapper.createObjectNode();
        for(var table:List.of("logistics_dataset","logistics_provider","logistics_channel","logistics_version","logistics_rule","logistics_area","logistics_condition",
                "logistics_billing_acceptance","logistics_required_revision","logistics_import_batch","logistics_import_file","finance_setting","quotation_template","quotation_draft","quotation_record","logistics_company_binding")){
            var rows=snapshot.putArray(table);jdbc.sql("select to_jsonb(t)::text from "+table+" t").query((rs,n)->mapper.readTree(rs.getString(1))).list().forEach(rows::add);
        }
        // Preserve every original quotation byte-for-byte and the exact referenced prices independently of runtime prices.
        jdbc.sql("""
            insert into logistics_quotation_history(quotation_id,snapshot)
            select q.id,jsonb_build_object('quotation',q.payload,'versions',coalesce((
              select jsonb_agg(jsonb_build_object('versionId',v.id,'channelId',c.id,'channel',c.payload,'provider',p.payload,'price',v.payload))
              from logistics_version v join logistics_channel c on c.id=v.channel_id join logistics_provider p on p.id=c.provider_id
              where exists(select 1 from jsonb_array_elements(coalesce(q.payload->'quoteOptions','[]'::jsonb)) o
                where o->>'logisticsVersionId'=v.id::text)), '[]'::jsonb))
            from quotation_record q on conflict(quotation_id) do nothing
            """).update();
        if(count("logistics_quotation_history")<count("quotation_record"))throw AppException.conflict("历史报价快照未覆盖全部记录");
        var bytes=mapper.writeValueAsBytes(snapshot);var key="logistics/backups/company-rebuild/"+id+".json";
        storage.putRaw(key,new ByteArrayInputStream(bytes),bytes.length,"application/json");
        var sha=AssetStorageService.sha256(bytes);try(var in=storage.openRaw(key)){if(!sha.equals(AssetStorageService.sha256(in.readAllBytes())))throw AppException.conflict("备份回读校验失败");}catch(IOException e){throw AppException.conflict("备份回读失败");}
        var payload=(ObjectNode)job.path("payload").deepCopy();payload.putObject("backup").put("objectKey",key).put("sha256",sha);
        payload.put("historyFingerprint",historyFingerprint()).put("priceFingerprint",priceFingerprint());
        var keys=payload.putArray("priceObjectKeys");var distinct=new TreeSet<String>();
        for(var batch:snapshot.path("logistics_import_batch"))collectPriceObjects(batch.path("payload"),distinct);
        distinct.forEach(keys::add);
        return save(id,"backed-up",payload);
    }
    @Transactional
    public ObjectNode purge(UUID id,ObjectNode input){
        var job=locked(id);if(job.path("phase").asText().equals("deleted"))return job;
        if(!job.path("phase").asText().equals("backed-up"))throw AppException.conflict("必须先完成可回读的备份");
        if(!input.path("deleteConfirmed").asBoolean())throw AppException.unprocessable("未确认清理预览中的旧价格");
        var payload=(ObjectNode)job.path("payload").deepCopy();verifyBackup(payload);
        jdbc.sql("lock table quotation_record,quotation_draft,quotation_template,finance_setting in share row exclusive mode").update();
        if(!payload.path("historyFingerprint").asText().equals(historyFingerprint())||!payload.path("priceFingerprint").asText().equals(priceFingerprint()))throw AppException.conflict("备份后数据已变化，必须重新核对备份");
        save(id,"deleting",payload);jdbc.sql("select set_config('app.logistics_purge_job',:id,true)").param("id",id.toString()).query(String.class).single();
        var sources=new ArrayList<UUID>();payload.path("sourceDatasets").forEach(v->sources.add(UUID.fromString(v.asText())));
        if(sources.isEmpty())throw AppException.conflict("清理范围为空");
        jdbc.sql("update logistics_channel set current_version_id=null where dataset_id in (:sources)").param("sources",sources).update();
        jdbc.sql("delete from logistics_billing_acceptance where version_id in(select v.id from logistics_version v join logistics_channel c on c.id=v.channel_id where c.dataset_id in (:sources))").param("sources",sources).update();
        var removed=jdbc.sql("delete from logistics_version where channel_id in(select id from logistics_channel where dataset_id in (:sources))").param("sources",sources).update();
        // Rule/area/condition rows cascade from precisely selected versions. Import payloads can also contain prices.
        jdbc.sql("update logistics_import_batch set status='completed',phase='purged',lease_id=null,payload=jsonb_build_object('purgedByRebuild',cast(:id as text),'progress',100,'files','[]'::jsonb,'results','[]'::jsonb),updated_at=now() where dataset_id in (:sources)")
                .param("id",id.toString()).param("sources",sources).update();
        jdbc.sql("update logistics_import_file set status='delete-pending',updated_at=now() where batch_id in(select id from logistics_import_batch where dataset_id in (:sources)) and status<>'deleted'").param("sources",sources).update();
        // Keep source dataset/channel identifiers for audit; their old price records no longer exist.
        jdbc.sql("update logistics_dataset set payload=payload-'activation',revision=revision+1 where id in (:sources)").param("sources",sources).update();
        jdbc.sql("update quotation_draft set payload=payload || '{\"logisticsRepriceRequired\":true,\"logisticsRevision\":\"\"}'::jsonb,version=version+1,updated_at=now()").update();
        if(!payload.path("historyFingerprint").asText().equals(historyFingerprint()))throw AppException.conflict("历史报价校验失败，清理事务已回滚");
        payload.put("removedVersions",removed).put("historyPreserved",true);return save(id,"deleted",payload);
    }
    @Transactional
    public ObjectNode cleanupObjects(UUID id){
        var job=locked(id);if(!Set.of("deleted","completed").contains(job.path("phase").asText()))throw AppException.conflict("旧价格尚未清理");
        var payload=(ObjectNode)job.path("payload").deepCopy();var pending=mapper.createArrayNode();
        for(var value:payload.path("priceObjectKeys")){
            var key=value.asText();if(!(key.startsWith("logistics/imports/")||key.startsWith("logistics/evidence/")))throw AppException.conflict("价格副本路径超出清理范围");
            if(!storage.removeRaw(key))pending.add(key);
        }
        payload.set("priceObjectKeys",pending);payload.put("priceObjectsPending",pending.size());return save(id,job.path("phase").asText(),payload);
    }
    @Transactional
    public ObjectNode finish(UUID id,ObjectNode input,String actor){
        var job=locked(id);if(job.path("phase").asText().equals("completed"))return job;
        if(!job.path("phase").asText().equals("deleted"))throw AppException.conflict("旧价格尚未清理");requireNote(input);
        if(!input.path("reviewConfirmed").asBoolean())throw AppException.unprocessable("请确认基准价格审核结果");
        var payload=(ObjectNode)job.path("payload").deepCopy();var target=UUID.fromString(payload.path("targetDatasetId").asText());
        if(!payload.path("priceObjectKeys").isEmpty())throw AppException.conflict("旧价格文件副本仍有待清理项");
        if(jdbc.sql("select count(*) from logistics_import_batch where dataset_id=:id and status in ('processing','queued')").param("id",target).query(Long.class).single()>0)throw AppException.conflict("仍有导入批次未完成");
        var channels=jdbc.sql("""
            select jsonb_build_object('companyChannelId',b.company_channel_id,'channelId',c.id,
              'channelKey',concat_ws('::',c.rule_id,p.payload->>'name',c.code),'name',c.payload->>'name',
              'ready',c.current_version_id is not null and logistics_version_quote_ready(c.current_version_id),
              'hasPrice',exists(select 1 from logistics_version v where v.channel_id=c.id))::text
            from logistics_company_binding b join logistics_channel c on c.id=b.channel_id join logistics_provider p on p.id=c.provider_id
            where b.dataset_id=:id
            """).param("id",target).query((rs,n)->mapper.readTree(rs.getString(1))).list();
        var index=new HashMap<String,JsonNode>();channels.forEach(c->index.put(c.path("companyChannelId").asText(),c));
        var blocked=mapper.createArrayNode();var missing=mapper.createArrayNode();int ready=0;
        for(var entry:directory.snapshot().path("entries"))if(entry.path("enabled").asBoolean()){
            var c=index.get(entry.path("id").asText());if(c==null||!c.path("hasPrice").asBoolean())missing.add(entry.path("channelName").asText());
            else if(!c.path("ready").asBoolean())blocked.add(entry.path("channelName").asText());else ready++;
        }
        if(!missing.isEmpty())throw AppException.unprocessable("基准渠道缺少导入结果："+missing);
        if(ready==0)throw AppException.unprocessable("尚无通过价格审核和计费验收的渠道");
        if(!blocked.isEmpty()&&!input.path("unavailableConfirmed").asBoolean())throw AppException.unprocessable("仍有不可报价渠道，需明确确认保留阻断："+blocked);
        var mappings=new LinkedHashMap<String,String>();var changes=payload.putArray("bindingChanges");
        for(var old:payload.path("channelMappings")){
            var fresh=index.get(old.path("companyChannelId").asText());
            if(fresh!=null&&fresh.path("ready").asBoolean())mappings.put(old.path("channelKey").asText(),fresh.path("channelKey").asText());
            changes.addObject().put("before",old.path("channelKey").asText()).put("after",mappings.getOrDefault(old.path("channelKey").asText(),"")).put("status",fresh==null?"removed":fresh.path("ready").asBoolean()?"mapped":"unavailable");
        }
        migrateBindings(mappings);
        jdbc.sql("update logistics_dataset set status='archived',revision=revision+1 where status='active'").update();
        jdbc.sql("update logistics_dataset set status='active',activated_at=now(),revision=revision+1 where id=:id").param("id",target).update();
        payload.set("blockedChannels",blocked);payload.put("readyChannels",ready).put("completedBy",actor).put("completionNote",input.path("note").asText());
        jdbc.sql("update logistics_company_state set paused=false,target_dataset_id=null,revision=revision+1 where singleton").update();
        return save(id,"completed",payload);
    }
    private void migrateBindings(Map<String,String> mapping){
        for(var key:List.of("channel-policies","tax-settings")){
            var raw=jdbc.sql("select payload::text from finance_setting where setting_key=:key for update").param("key",key).query(String.class).optional();
            if(raw.isEmpty())continue;var node=mapper.readTree(raw.get());remap(node,mapping,key.equals("channel-policies"));
            jdbc.sql("update finance_setting set payload=cast(:p as jsonb),version=version+1,updated_at=now() where setting_key=:key").param("p",node.toString()).param("key",key).update();
        }
        for(var row:jdbc.sql("select id,payload::text from quotation_template for update").query((rs,n)->Map.entry(rs.getObject(1,UUID.class),mapper.readTree(rs.getString(2)))).list()){
            remap(row.getValue(),mapping,false);((ObjectNode)row.getValue()).put("logisticsRepriceRequired",true);
            jdbc.sql("update quotation_template set payload=cast(:p as jsonb),version=version+1,updated_at=now() where id=:id").param("id",row.getKey()).param("p",row.getValue().toString()).update();
        }
    }
    static void remap(JsonNode node,Map<String,String> mapping,boolean removeMissing){
        if(node.isObject()){
            var object=(ObjectNode)node;
            if(object.path("channelKey").isTextual()){
                var key=object.path("channelKey").asText();var replacement=mapping.get(key);
                if(replacement!=null){object.put("channelKey",replacement);object.remove("channelUnavailable");}
                else if(!key.isBlank())object.put("channelUnavailable",true);
            }
            for(var field:object.properties()){
                if(field.getKey().equals("allowedChannels")&&field.getValue().isArray()){
                    var replacement=new LinkedHashSet<String>();for(var old:field.getValue()){var mapped=mapping.get(old.asText());if(mapped!=null)replacement.add(mapped);else if(!removeMissing)replacement.add(old.asText());}
                    var arr=(ArrayNode)field.getValue();arr.removeAll();replacement.forEach(arr::add);
                }else remap(field.getValue(),mapping,removeMissing);
            }
        }else if(node.isArray())for(var child:node)remap(child,mapping,removeMissing);
    }
    public ObjectNode job(UUID id){return jdbc.sql("select to_jsonb(j)::text from logistics_company_rebuild j where id=:id").param("id",id).query((rs,n)->(ObjectNode)mapper.readTree(rs.getString(1))).optional().orElseThrow(()->AppException.notFound("重建任务不存在"));}
    private ObjectNode locked(UUID id){var state=directory.state(true);if(!id.toString().equals(state.path("rebuild_id").asText()))throw AppException.conflict("重建任务已变化");return job(id);}
    private ObjectNode save(UUID id,String phase,ObjectNode payload){jdbc.sql("update logistics_company_rebuild set phase=:phase,payload=cast(:p as jsonb),updated_at=now() where id=:id").param("phase",phase).param("p",payload.toString()).param("id",id).update();return job(id);}
    private void verifyBackup(JsonNode payload){try(var in=storage.openRaw(payload.path("backup").path("objectKey").asText())){if(!AssetStorageService.sha256(in.readAllBytes()).equals(payload.path("backup").path("sha256").asText()))throw AppException.conflict("备份内容校验失败");}catch(IOException e){throw AppException.conflict("备份无法读取");}}
    private long count(String table){return jdbc.sql("select count(*) from "+table).query(Long.class).single();}
    private String historyFingerprint(){return jdbc.sql("select md5(coalesce(string_agg(id::text || payload::text,'|' order by id),'')) from quotation_record").query(String.class).single();}
    private String priceFingerprint(){return jdbc.sql("select md5(coalesce(string_agg(id::text || payload::text,'|' order by id),'')) from logistics_version").query(String.class).single();}
    private List<JsonNode> oldChannels(){return jdbc.sql("select jsonb_build_object('oldChannelId',c.id,'providerName',p.payload->>'name','channelName',c.payload->>'name','channelKey',concat_ws('::',c.rule_id,p.payload->>'name',c.code))::text from logistics_channel c join logistics_provider p on p.id=c.provider_id order by c.id").query((rs,n)->mapper.readTree(rs.getString(1))).list();}
    private static void requireNote(JsonNode body){if(body.path("note").asText().isBlank())throw AppException.unprocessable("请填写操作核对备注");}
    private static void collectPriceObjects(JsonNode node,Set<String> keys){if(node.isObject())for(var f:node.properties()){
        if(f.getKey().equals("objectKey")&&f.getValue().isTextual()){var key=f.getValue().asText();if(key.startsWith("logistics/imports/")||key.startsWith("logistics/evidence/"))keys.add(key);}
        else collectPriceObjects(f.getValue(),keys);
    }else if(node.isArray())for(var child:node)collectPriceObjects(child,keys);}
}
