package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

@Service
public class LogisticsDraftReviewService {
    private static final Set<String> EDITABLE=Set.of("weightFromKg","weightToKg","weightFromInclusive","weightToInclusive",
            "pricePerKg","registrationFee","firstWeightKg","firstWeightPrice","nextWeightKg","nextWeightPrice","intervalPrice","surcharge");
    private final JdbcClient jdbc;private final ObjectMapper mapper;private final LogisticsWorkbookService workbooks;private final LogisticsDatasetGuard guard;
    public LogisticsDraftReviewService(JdbcClient jdbc,ObjectMapper mapper,LogisticsWorkbookService workbooks,LogisticsDatasetGuard guard){this.jdbc=jdbc;this.mapper=mapper;this.workbooks=workbooks;this.guard=guard;}

    @Transactional public ObjectNode patch(UUID versionId,ObjectNode input,String actor){
        var stored=load(versionId,true);guard.channel(UUID.fromString(stored.path("channelId").asText()));
        if(!stored.path("status").asText().equals("draft"))throw AppException.conflict("只有待审核草稿可以修改");
        var fingerprint=input.path("fingerprint").asText();if(fingerprint.isBlank()||!fingerprint.equals(stored.path("fingerprint").asText()))throw AppException.conflict("价格已被其他人修改，请刷新后重试");
        var changes=input.path("changes");var etaChanges=input.path("etaChanges");
        int changeCount=(changes.isArray()?changes.size():0)+(etaChanges.isArray()?etaChanges.size():0);
        if(changeCount<1||changeCount>5000)throw AppException.unprocessable("请选择1至5000条价格或时效修正");
        if(!changes.isMissingNode()&&!changes.isArray())throw AppException.unprocessable("价格修正格式不正确");
        if(!etaChanges.isMissingNode()&&!etaChanges.isArray())throw AppException.unprocessable("时效修正格式不正确");
        var payload=(ObjectNode)stored.deepCopy();payload.remove("fingerprint");LogisticsReadiness.apply(payload,mapper);var rows=(ArrayNode)payload.withArray("rows");var byKey=new LinkedHashMap<String,ObjectNode>();
        rows.forEach(row->byKey.put(row.path("rowKey").asText(),(ObjectNode)row));var audit=mapper.createArrayNode();
        for(var change:changes){
            var row=byKey.get(change.path("rowKey").asText());if(row==null)throw AppException.conflict("待修改价格行已变化，请刷新后重试");
            var fields=change.path("fields");if(!fields.isObject()||fields.isEmpty())throw AppException.unprocessable("修正字段不能为空");
            var before=mapper.createObjectNode();var after=mapper.createObjectNode();
            var names=new ArrayList<String>();fields.propertyNames().forEach(names::add);
            for(var field:names){if(!EDITABLE.contains(field))throw AppException.unprocessable("不允许修改物流商、渠道或原始来源字段："+field);var value=fields.path(field);before.set(field,row.path(field).deepCopy());
                if(field.endsWith("Inclusive")){if(!value.isBoolean())throw AppException.unprocessable("区间边界包含方式必须是布尔值");row.put(field,value.asBoolean());after.put(field,value.asBoolean());}
                else {if(!value.isNumber()||!Double.isFinite(value.asDouble())||value.asDouble()<0)throw AppException.unprocessable("重量和价格必须是非负数字："+field);row.put(field,value.decimalValue());after.put(field,value.decimalValue());}}
            audit.addObject().put("sourceSheet",row.path("sourceSheet").asText()).put("sourceRow",row.path("sourceRow").asInt()).set("before",before);((ObjectNode)audit.get(audit.size()-1)).set("after",after);
        }
        var etaAudit=mapper.createArrayNode();var byRoute=new LinkedHashMap<String,List<ObjectNode>>();
        rows.forEach(value->{var row=(ObjectNode)value;var routeKey=row.path("routeKey").asText();if(routeKey.isBlank()){routeKey=LogisticsReadiness.routeKey(row);row.put("routeKey",routeKey);}byRoute.computeIfAbsent(routeKey,ignored->new ArrayList<>()).add(row);});
        var changedRoutes=new HashSet<String>();
        for(var change:etaChanges){
            var routeKey=change.path("routeKey").asText();if(routeKey.isBlank()||!changedRoutes.add(routeKey))throw AppException.unprocessable("每条路线只能提交一次时效修正");
            var min=change.path("etaMinDays");var max=change.path("etaMaxDays");
            if(!min.isIntegralNumber()||!max.isIntegralNumber()||min.asInt()<=0||max.asInt()<min.asInt()||max.asInt()>365)throw AppException.unprocessable("时效必须是1至365天内的有效起止范围");
            var routeRows=byRoute.get(routeKey);if(routeRows==null||routeRows.isEmpty())throw AppException.conflict("待修改时效路线已变化，请刷新后重试");
            var beforeValues=mapper.createArrayNode();var seenBefore=new LinkedHashSet<String>();
            for(var row:routeRows){var signature=row.path("etaMinDays").asInt()+"-"+row.path("etaMaxDays").asInt()+"|"+row.path("etaSource").asText();if(seenBefore.add(signature))beforeValues.addObject().put("etaMinDays",row.path("etaMinDays").asInt()).put("etaMaxDays",row.path("etaMaxDays").asInt()).put("etaSource",row.path("etaSource").asText());row.put("etaMinDays",min.asInt()).put("etaMaxDays",max.asInt()).put("etaSource","manual-review");}
            etaAudit.addObject().put("routeKey",routeKey).put("affectedRows",routeRows.size()).set("before",beforeValues);
            ((ObjectNode)etaAudit.get(etaAudit.size()-1)).set("after",mapper.createObjectNode().put("etaMinDays",min.asInt()).put("etaMaxDays",max.asInt()).put("etaSource","manual-review"));
        }
        var preserved=mapper.createArrayNode();for(var issue:payload.path("issues"))if(!isEditableIssue(issue))preserved.add(issue.deepCopy());
        var generated=workbooks.validateEditableRows(rows);preserved.addAll(generated);payload.set("issues",preserved);
        payload.put("errors",count(preserved,"error"));
        LogisticsReadiness.apply(payload,mapper);
        payload.put("validRows",rows.size()).put("contentHash",LogisticsDatasetService.hash(rows.toString()));
        var previous=mapper.createArrayNode();var baseline=payload.path("basePublishedVersionId").asText();if(!baseline.isBlank())jdbc.sql("select payload->'rows' from logistics_version where id=:id").param("id",UUID.fromString(baseline)).query(String.class).optional().ifPresent(raw->{try{previous.addAll((ArrayNode)mapper.readTree(raw));}catch(Exception ignored){}});
        var comparison=workbooks.compare(rows,previous);payload.set("diffRows",comparison.path("diffRows"));payload.set("summary",comparison.path("summary"));
        var editedAt=java.time.Instant.now().toString();payload.put("lastEditedBy",actor).put("lastEditedAt",editedAt);
        var history=payload.withArray("correctionHistory");var event=history.addObject().put("editedBy",actor).put("editedAt",editedAt);event.set("changes",audit);event.set("etaChanges",etaAudit);
        var updated=jdbc.sql("update logistics_version set payload=cast(:payload as jsonb) where id=:id and status='draft' and rows_fingerprint=:fingerprint")
                .param("payload",payload.toString()).param("id",versionId).param("fingerprint",fingerprint).update();
        if(updated!=1)throw AppException.conflict("价格已被其他人修改，请刷新后重试");var result=load(versionId,false);syncBatch(result);return result;
    }
    ObjectNode load(UUID id,boolean lock){var sql="select jsonb_build_object('id',v.id,'channelId',v.channel_id,'versionNumber',v.version_number,'status',v.status,'payload',v.payload,'fingerprint',v.rows_fingerprint)::text from logistics_version v where v.id=:id"+(lock?" for update":"");
        var row=jdbc.sql(sql).param("id",id).query((rs,n)->(ObjectNode)mapper.readTree(rs.getString(1))).optional().orElseThrow(()->AppException.notFound("物流版本不存在"));
        var result=(ObjectNode)row.path("payload").deepCopy();LogisticsReadiness.apply(result,mapper);
        var pricingReady=result.path("quoteReady").asBoolean(false);
        result.put("pricingReady",pricingReady);
        result.put("id",row.path("id").asText()).put("channelId",row.path("channelId").asText()).put("versionNumber",row.path("versionNumber").asInt()).put("status",row.path("status").asText()).put("fingerprint",row.path("fingerprint").asText()).put("quoteReady","published".equals(row.path("status").asText())&&pricingReady);return result;}
    private static boolean isEditableIssue(JsonNode issue){var code=issue.path("code").asText();if(code.startsWith("WEIGHT_")||code.startsWith("ETA_"))return true;var field=issue.path("field").asText();return Set.of("重量区间","计费价格","区域/重量区间","重量连续性","时效","参考时效").contains(field);}
    private static int count(ArrayNode issues,String level){int count=0;for(var issue:issues)if(level.equals(issue.path("level").asText()))count++;return count;}
    private void syncBatch(ObjectNode version){var batchId=version.path("batchId").asText();if(batchId.isBlank())return;UUID id;try{id=UUID.fromString(batchId);}catch(Exception ignored){return;}
        var raw=jdbc.sql("select payload::text from logistics_import_batch where id=:id for update").param("id",id).query(String.class).optional();if(raw.isEmpty())return;
        try{var payload=(ObjectNode)mapper.readTree(raw.get());for(var value:payload.withArray("results"))if(version.path("id").asText().equals(value.path("versionId").asText())){var item=(ObjectNode)value;item.put("errors",version.path("errors").asInt()).put("pricingReady",version.path("pricingReady").asBoolean()).put("etaReady",version.path("etaReady").asBoolean()).put("etaMissingCount",version.path("etaMissingCount").asInt());item.set("issues",version.path("issues").deepCopy());item.set("summary",version.path("summary").deepCopy());item.set("missingEtaRoutes",version.path("missingEtaRoutes").deepCopy());item.set("blockingReasons",version.path("blockingReasons").deepCopy());item.set("reviewWarnings",version.path("reviewWarnings").deepCopy());break;}LogisticsReadiness.applyBatch(payload);
            jdbc.sql("update logistics_import_batch set payload=cast(:payload as jsonb),updated_at=now() where id=:id").param("payload",payload.toString()).param("id",id).update();}catch(Exception e){throw new IllegalStateException(e);}}
}
