package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

@Service
public class LogisticsBatchPublishService {
    private final JdbcClient jdbc;private final ObjectMapper mapper;private final LogisticsService logistics;private final LogisticsBillingAcceptanceService billing;private final TransactionTemplate tx;
    public LogisticsBatchPublishService(JdbcClient jdbc,ObjectMapper mapper,LogisticsService logistics,LogisticsBillingAcceptanceService billing,PlatformTransactionManager manager){this.jdbc=jdbc;this.mapper=mapper;this.logistics=logistics;this.billing=billing;this.tx=new TransactionTemplate(manager);}

    public ObjectNode progress(UUID batchId){
        if(!jdbc.sql("select exists(select 1 from logistics_import_batch where id=:id)").param("id",batchId).query(Boolean.class).single())throw AppException.notFound("导入批次不存在");
        var ids=jdbc.sql("""
            select distinct v.id::text from logistics_import_batch b
            cross join lateral jsonb_array_elements(b.payload->'results') r
            join logistics_version v on v.id::text=r->>'versionId'
            join logistics_channel c on c.current_version_id=v.id
            where b.id=:id and v.status='published'
            """).param("id",batchId).query(String.class).list();
        var result=mapper.createObjectNode().put("batchId",batchId.toString());
        var published=result.putArray("publishedVersionIds");ids.forEach(published::add);return result;
    }

    public ObjectNode publishReady(UUID batchId,ObjectNode input,String actor){
        var batch=jdbc.sql("select payload::text from logistics_import_batch where id=:id").param("id",batchId).query(String.class).optional().orElseThrow(()->AppException.notFound("导入批次不存在"));
        ObjectNode payload;try{payload=(ObjectNode)mapper.readTree(batch);}catch(Exception e){throw new IllegalStateException(e);}
        var requested=new LinkedHashMap<UUID,ObjectNode>();for(var item:input.withArray("selections"))if(!item.path("versionId").asText().isBlank())requested.put(uuid(item.path("versionId").asText()),(ObjectNode)item);
        var candidates=new LinkedHashMap<UUID,ObjectNode>();for(var item:payload.withArray("results"))if(!item.path("versionId").asText().isBlank()){
            var id=uuid(item.path("versionId").asText());if(requested.isEmpty()||requested.containsKey(id))candidates.put(id,(ObjectNode)item);}
        if(candidates.isEmpty())throw AppException.unprocessable("本批次没有可发布的渠道版本");
        var note=input.path("note").asText().trim();if(note.isBlank())throw AppException.unprocessable("一键发布审核备注不能为空");
        var result=mapper.createObjectNode();var published=result.putArray("published");var skipped=result.putArray("skipped");var failed=result.putArray("failed");
        for(var entry:candidates.entrySet()){
            var versionId=entry.getKey();var batchItem=entry.getValue();var selection=requested.getOrDefault(versionId,mapper.createObjectNode());
            try{
                var outcome=tx.execute(status->{var meta=meta(versionId);var channelId=uuid(meta.path("channelId").asText());
                    if(meta.path("status").asText().equals("published")&&meta.path("currentVersionId").asText().equals(versionId.toString())&&meta.path("quoteReady").asBoolean())return mapper.createObjectNode().put("alreadyPublished",true).set("version",meta);
                    if(!meta.path("status").asText().equals("draft"))throw AppException.conflict("版本已不再是待审核状态");
                    var version=logistics.publishReviewed(channelId,versionId,note,actor,selection.path("removalConfirmed").asBoolean(false),selection.path("reviewConfirmed").asBoolean(false));
                    billing.approveValidatedImport(versionId,actor,note);return mapper.createObjectNode().put("alreadyPublished",false).set("version",version);});
                var item=published.addObject().put("versionId",versionId.toString()).put("channelId",batchItem.path("channelId").asText()).put("providerName",batchItem.path("providerName").asText()).put("channelName",batchItem.path("channelName").asText()).put("quoteReady",true).put("alreadyPublished",outcome.path("alreadyPublished").asBoolean());
                item.put("message",item.path("alreadyPublished").asBoolean()?"该版本已发布，无需重复生成":"价格已发布；已有财务绑定自动更新，新渠道进入财务可选列表");
            }catch(AppException e){skipped.addObject().put("versionId",versionId.toString()).put("channelId",batchItem.path("channelId").asText()).put("providerName",batchItem.path("providerName").asText()).put("channelName",batchItem.path("channelName").asText()).put("reason",e.getMessage());}
            catch(Exception e){failed.addObject().put("versionId",versionId.toString()).put("channelId",batchItem.path("channelId").asText()).put("providerName",batchItem.path("providerName").asText()).put("channelName",batchItem.path("channelName").asText()).put("reason","发布失败，其他渠道未受影响");}
        }
        return result.put("batchId",batchId.toString()).put("publishedCount",published.size()).put("skippedCount",skipped.size()).put("failedCount",failed.size());
    }
    private ObjectNode meta(UUID id){return jdbc.sql("select jsonb_build_object('id',v.id,'channelId',v.channel_id,'currentVersionId',c.current_version_id,'status',v.status,'quoteReady',logistics_version_quote_ready(v.id))::text from logistics_version v join logistics_channel c on c.id=v.channel_id where v.id=:id")
            .param("id",id).query((rs,n)->(ObjectNode)mapper.readTree(rs.getString(1))).optional().orElseThrow(()->AppException.notFound("物流版本不存在"));}
    private static UUID uuid(String value){try{return UUID.fromString(value);}catch(Exception e){throw AppException.unprocessable("版本标识无效");}}
}
