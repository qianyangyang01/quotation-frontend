package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;

@Service
public class LogisticsExportService {
    private final JdbcClient jdbc; private final ObjectMapper mapper;
    public LogisticsExportService(JdbcClient jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public String priceSnapshot(UUID dataset,UUID versionId,String query,String country,String attribute) {
        var rows=jdbc.sql("""
                select jsonb_build_array(v.id,v.status,md5(v.payload::text),p.payload,c.payload)::text
                from logistics_channel c join logistics_provider p on p.id=c.provider_id join logistics_version v on v.channel_id=c.id
                where c.dataset_id=:dataset and ((cast(:version as uuid) is null and v.id=c.current_version_id) or v.id=cast(:version as uuid)) order by v.id
                """).param("dataset",dataset).param("version",versionId==null?null:versionId.toString()).query(String.class).list();
        if(rows.isEmpty())throw AppException.unprocessable("没有可导出的价格版本，请先审核价格或选择具体版本");
        return LogisticsDatasetService.hash(mapper.valueToTree(List.of(dataset.toString(),rows,query,country,attribute)).toString());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public byte[] prices(UUID dataset,UUID versionId,String query,String country,String attribute) {
        return prices(dataset,versionId,query,country,attribute,null);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public byte[] prices(UUID dataset,UUID versionId,String query,String country,String attribute,String snapshot) {
        if(snapshot!=null&&!snapshot.equals(priceSnapshot(dataset,versionId,query,country,attribute)))throw AppException.conflict("价格版本已变化，请重新生成下载链接");
        var versions=jdbc.sql("""
                select (v.payload || jsonb_build_object('quoteReady',logistics_version_quote_ready(v.id)))::text as payload,p.payload->>'name' as provider,c.payload->>'name' as channel,
                c.payload->>'logisticsAttribute' as attribute,v.id::text as id,v.status
                from logistics_channel c join logistics_provider p on p.id=c.provider_id
                join logistics_version v on v.channel_id=c.id
                where c.dataset_id=:dataset and ((cast(:version as uuid) is null and v.id=c.current_version_id) or v.id=cast(:version as uuid))
                order by p.payload->>'name',c.payload->>'name',v.version_number
                """).param("dataset",dataset).param("version",versionId==null?null:versionId.toString())
                .query((rs,n)->mapper.createObjectNode().put("provider",rs.getString("provider")).put("channel",rs.getString("channel"))
                        .put("attribute",rs.getString("attribute")).put("id",rs.getString("id")).put("status",rs.getString("status")).set("version",mapper.readTree(rs.getString("payload")))).list();
        try(var book=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("价格明细");var headers=new ArrayList<>(LogisticsWorkbookService.HEADERS);headers.addAll(LogisticsSourceParser.EXTRA_HEADERS);header(book,sheet,headers);
            var metadata=book.createSheet("版本信息");textRow(metadata,0,List.of("MILANO_LOGISTICS_METADATA_V1","导出时间",Instant.now().toString(),"数据集",dataset.toString()));
            textRow(metadata,1,List.of("物流商","渠道","版本ID","版本号","状态","原文件","导入时间","生效时间","可自动报价"));
            var rules=book.createSheet("规则说明");textRow(rules,0,List.of("MILANO_LOGISTICS_METADATA_V1","规则仅用于审阅，不会作为导入指令执行"));
            textRow(rules,1,List.of("物流商","渠道","原表规则说明"));int rowNumber=1,metadataRow=2,rulesRow=2;
            for(var v:versions) {
                if(!query.isBlank() && !(v.path("provider").asText()+v.path("channel").asText()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))continue;
                if(!attribute.isBlank()&&!attribute.equals(v.path("attribute").asText()))continue;
                boolean included=false;
                for(var value:v.path("version").path("rows")) {
                    if(!country.isBlank()&&!country.equalsIgnoreCase(value.path("countryCode").asText())&&!country.equals(value.path("areaName").asText()))continue;
                    var row=sheet.createRow(rowNumber++);
                    for(int col=0;col<LogisticsWorkbookService.KEYS.length;col++)cell(row,col,value.path(LogisticsWorkbookService.KEYS[col]));
                    int c=LogisticsWorkbookService.KEYS.length;
                    cell(row,c++,v.path("provider"));cell(row,c++,v.path("channel"));cell(row,c++,v.path("attribute"));
                    row.createCell(c++).setCellValue(value.path("currency").asText("CNY"));
                    row.createCell(c++).setCellValue(value.path("pricingModel").asText(value.path("firstWeightPrice").asDouble()>0?"first-next":value.path("intervalPrice").asDouble()>0?"interval":"per-kg"));
                    row.createCell(c++).setCellValue(value.path("weightFromInclusive").asBoolean(false)?"是":"否");
                    row.createCell(c++).setCellValue(value.path("weightToInclusive").asBoolean(true)?"是":"否");
                    cell(row,c++,value.path("originRegion"));cell(row,c++,value.path("billingStepKg"));cell(row,c++,value.path("notes"));
                    cell(row,c++,value.path("sourceSheet"));cell(row,c++,value.path("sourceRow"));cell(row,c++,value.path("pendingReason"));cell(row,c,value.path("linehaulPerKg"));included=true;
                }
                if(included) {
                    var version=v.path("version");textRow(metadata,metadataRow++,List.of(v.path("provider").asText(),v.path("channel").asText(),v.path("id").asText(),version.path("versionNumber").asText(),v.path("status").asText(),version.path("fileName").asText(),version.path("importedAt").asText(),version.path("publishedAt").asText(),version.path("quoteReady").asBoolean(true)?"是":"待适配"));
                    textRow(rules,rulesRow++,List.of(v.path("provider").asText(),v.path("channel").asText(),version.path("sourceNotes").asText()));
                }
            }
            sheet.createFreezePane(2,1);sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(0,rowNumber-1),0,headers.size()-1));
            finish(book);book.write(bytes);return bytes.toByteArray();
        }catch(Exception e){throw new IllegalStateException("价格Excel生成失败",e);}
    }
    public String standardizedSnapshot(UUID batchId,UUID versionId){
        if((batchId==null)==(versionId==null))throw AppException.unprocessable("必须且只能指定一个审核批次或版本");
        var values=new ArrayList<String>();
        if(batchId!=null){
            var batch=jdbc.sql("select status||'|'||md5(payload::text) from logistics_import_batch where id=:id").param("id",batchId).query(String.class).optional().orElseThrow(()->AppException.notFound("导入批次不存在"));values.add(batch);
            var payload=mapper.readTree(jdbc.sql("select payload::text from logistics_import_batch where id=:id").param("id",batchId).query(String.class).single());
            for(var result:payload.path("results"))if(!result.path("versionId").asText().isBlank())values.add(versionFingerprint(UUID.fromString(result.path("versionId").asText())));
        }else values.add(versionFingerprint(versionId));
        return LogisticsDatasetService.hash(mapper.valueToTree(values).toString());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public byte[] standardized(UUID batchId,UUID versionId,String snapshot){
        if(snapshot!=null&&!snapshot.equals(standardizedSnapshot(batchId,versionId)))throw AppException.conflict("审核数据已变化，请重新生成下载链接");
        ObjectNode batch=null;var records=new ArrayList<ObjectNode>();
        if(batchId!=null){
            batch=(ObjectNode)mapper.readTree(jdbc.sql("select payload::text from logistics_import_batch where id=:id").param("id",batchId).query(String.class).optional().orElseThrow(()->AppException.notFound("导入批次不存在")));
            var seen=new LinkedHashSet<UUID>();
            for(var result:batch.path("results")){
                var rawId=result.path("versionId").asText();
                if(!rawId.isBlank()){var id=UUID.fromString(rawId);if(seen.add(id))records.add(versionRecord(id));}
                else if(result.path("parsed").isObject())records.add(mapper.createObjectNode().put("provider",result.path("providerName").asText()).put("channel",result.path("channelName").asText()).put("status",result.path("status").asText()).set("version",result.path("parsed").deepCopy()));
            }
        }else records.add(versionRecord(versionId));
        if(records.isEmpty()&&batch==null)throw AppException.unprocessable("没有可导出的审核数据");
        try(var book=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()){
            var detail=book.createSheet("关键字段");textRow(detail,0,List.of("MILANO_LOGISTICS_REVIEW_V1","仅供审核，不作为导入模板",Instant.now().toString()));
            var detailHeaders=List.of("物流商","渠道名称","原产品代码","国家地区","国家代码","目的分区","报价区域","重量段","起点包含","终点包含","计费模型","公斤价","每票费/挂号费","原费用列名","首重KG","首重价","续重KG","续重价","时效最早天","时效最晚天","时效来源","校验状态","阻断原因","提醒","路线键","原文件","工作表","行号");
            header(book,detail,1,detailHeaders);var eta=book.createSheet("待补时效");textRow(eta,0,List.of("MILANO_LOGISTICS_REVIEW_V1","每条路线填写一次时效后在系统审核页批量应用"));
            header(book,eta,1,List.of("物流商","渠道名称","路线状态","国家地区","国家代码","目的分区","报价区域","原产品代码","路线键","来源工作表","来源行"));
            var issues=book.createSheet("问题清单");textRow(issues,0,List.of("MILANO_LOGISTICS_REVIEW_V1","阻断项禁止发布；提醒项需人工查看"));
            header(book,issues,1,List.of("物流商","渠道名称","级别","字段","原因","工作表","行号","路线键"));
            int dr=2,er=2,ir=2;var etaSeen=new LinkedHashSet<String>();
            for(var record:records){
                var version=(ObjectNode)record.path("version").deepCopy();LogisticsReadiness.apply(version,mapper);var provider=record.path("provider").asText();var channel=record.path("channel").asText();
                for(var value:version.path("rows")){
                    var row=(ObjectNode)value;var output=detail.createRow(dr++);int c=0;
                    stringCell(output,c++,provider);stringCell(output,c++,channel);stringCell(output,c++,display(row.path("sourceProductCode").asText(),"原表未提供"));
                    stringCell(output,c++,row.path("areaName").asText());stringCell(output,c++,row.path("countryCode").asText());stringCell(output,c++,row.path("zoneName").asText());stringCell(output,c++,display(row.path("originRegion").asText(),"原表未标注"));
                    stringCell(output,c++,weight(row));stringCell(output,c++,row.path("weightFromInclusive").asBoolean()?"是":"否");stringCell(output,c++,row.path("weightToInclusive").asBoolean(true)?"是":"否");
                    stringCell(output,c++,model(row.path("pricingModel").asText()));cell(output,c++,row.path("pricePerKg"));cell(output,c++,row.path("registrationFee"));stringCell(output,c++,row.path("sourceFeeLabel").asText());
                    cell(output,c++,row.path("firstWeightKg"));cell(output,c++,row.path("firstWeightPrice"));cell(output,c++,row.path("nextWeightKg"));cell(output,c++,row.path("nextWeightPrice"));
                    cell(output,c++,row.path("etaMinDays"));cell(output,c++,row.path("etaMaxDays"));stringCell(output,c++,row.path("etaSource").asText());
                    var blocking=row.path("blockingReason").asText();var warning=row.path("reviewWarning").asText();stringCell(output,c++,blocking.isBlank()?(warning.isBlank()?"通过":"提醒"):"阻断");stringCell(output,c++,blocking);stringCell(output,c++,warning);
                    stringCell(output,c++,row.path("routeKey").asText());stringCell(output,c++,row.path("sourceFile").asText(version.path("fileName").asText()));stringCell(output,c++,row.path("sourceSheet").asText());cell(output,c,row.path("sourceRow"));
                }
                for(var route:version.path("missingEtaRoutes")){
                    var key=provider+"|"+channel+"|"+route.path("routeKey").asText();if(!etaSeen.add(key))continue;var output=eta.createRow(er++);int c=0;
                    for(var text:List.of(provider,channel,etaStatus(route.path("status").asText()),route.path("areaName").asText(),route.path("countryCode").asText(),route.path("zoneName").asText(),display(route.path("originRegion").asText(),"原表未标注"),display(route.path("sourceProductCode").asText(),"原表未提供"),route.path("routeKey").asText(),route.path("sourceSheet").asText()))stringCell(output,c++,text);cell(output,c,route.path("sourceRow"));
                }
                for(var issue:version.path("issues")){var output=issues.createRow(ir++);issueRow(output,provider,channel,issue.path("level").asText().equals("error")?"阻断":"提醒",issue);}
                for(var value:version.path("rows")){
                    var row=(ObjectNode)value;
                    if(!row.path("blockingReason").asText().isBlank()){var issue=mapper.createObjectNode().put("field","校验规则").put("message",row.path("blockingReason").asText()).put("sourceSheet",row.path("sourceSheet").asText()).put("row",row.path("sourceRow").asInt()).put("routeKey",row.path("routeKey").asText());issueRow(issues.createRow(ir++),provider,channel,"阻断",issue);}
                    if(!row.path("reviewWarning").asText().isBlank()){var issue=mapper.createObjectNode().put("field","人工复核").put("message",row.path("reviewWarning").asText()).put("sourceSheet",row.path("sourceSheet").asText()).put("row",row.path("sourceRow").asInt()).put("routeKey",row.path("routeKey").asText());issueRow(issues.createRow(ir++),provider,channel,"提醒",issue);}
                }
            }
            if(batch!=null){
                for(var file:batch.path("fileReports"))if(Set.of("failed","template-pending").contains(file.path("status").asText())){var issue=mapper.createObjectNode().put("field","文件").put("message",file.path("message").asText()).put("sourceSheet",file.path("fileName").asText());issueRow(issues.createRow(ir++),"","", "阻断",issue);}
                for(var result:batch.path("results"))if(result.path("status").asText().equals("blocked")&&result.path("versionId").asText().isBlank()&&!result.path("parsed").isObject()){var issue=mapper.createObjectNode().put("field","导入").put("message",result.path("message").asText());issueRow(issues.createRow(ir++),result.path("providerName").asText(),result.path("channelName").asText(),"阻断",issue);}
            }
            detail.createFreezePane(0,2);eta.createFreezePane(0,2);issues.createFreezePane(0,2);detail.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(1,Math.max(1,dr-1),0,detailHeaders.size()-1));
            finishReview(book);book.write(bytes);return bytes.toByteArray();
        }catch(AppException e){throw e;}catch(Exception e){throw new IllegalStateException("关键字段Excel生成失败",e);}
    }
    private String versionFingerprint(UUID id){return jdbc.sql("select id::text||'|'||status||'|'||md5(payload::text) from logistics_version where id=:id").param("id",id).query(String.class).optional().orElseThrow(()->AppException.notFound("版本不存在"));}
    private ObjectNode versionRecord(UUID id){return jdbc.sql("select v.payload::text,p.payload->>'name',c.payload->>'name',v.status from logistics_version v join logistics_channel c on c.id=v.channel_id join logistics_provider p on p.id=c.provider_id where v.id=:id")
            .param("id",id).query((rs,n)->mapper.createObjectNode().put("provider",rs.getString(2)).put("channel",rs.getString(3)).put("status",rs.getString(4)).set("version",mapper.readTree(rs.getString(1)))).optional().orElseThrow(()->AppException.notFound("版本不存在"));}
    private static void issueRow(Row output,String provider,String channel,String level,JsonNode issue){int c=0;for(var text:List.of(provider,channel,level,issue.path("field").asText(),issue.path("message").asText(),issue.path("sourceSheet").asText()))stringCell(output,c++,text);cell(output,c++,issue.path("row"));stringCell(output,c,issue.path("routeKey").asText());}
    private static String display(String value,String fallback){return value==null||value.isBlank()?fallback:value;}
    private static String weight(JsonNode row){return (row.path("weightFromInclusive").asBoolean()?"[":"(")+row.path("weightFromKg").asText()+", "+row.path("weightToKg").asText()+(row.path("weightToInclusive").asBoolean(true)?"]":")")+" KG";}
    private static String model(String value){return value.equals("per-kg")?"公斤价＋每票费":value.equals("first-next")?"首重价＋续重价":"暂不支持（"+value+"）";}
    private static String etaStatus(String value){return switch(value){case "missing"->"缺失";case "partial"->"不完整";case "conflict"->"冲突";default->value;};}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public byte[] changes(UUID batchId,UUID versionId) {
        var ids=new LinkedHashSet<UUID>(); JsonNode batchPayload=mapper.createObjectNode();
        if(versionId!=null)ids.add(versionId);
        if(batchId!=null) {
            var batch=jdbc.sql("select payload::text from logistics_import_batch where id=:id").param("id",batchId).query(String.class).optional().orElseThrow(()->AppException.notFound("导入批次不存在"));
            batchPayload=mapper.readTree(batch);
            for(var item:batchPayload.path("results"))if(!item.path("versionId").asText().isBlank())ids.add(UUID.fromString(item.path("versionId").asText()));
        }
        if(ids.isEmpty()&&batchId==null)throw AppException.unprocessable("没有可导出的版本差异");
        try(var book=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
            var summary=book.createSheet("批次汇总");textRow(summary,0,List.of("MILANO_LOGISTICS_DIFF_V1","仅供审阅，不作为价格导入模板",Instant.now().toString()));
            textRow(summary,1,List.of("物流商","渠道","版本","基线版本","新增","调价","规则变化","重量区间变化","移除","覆盖缩小","未变","状态"));
            var detail=book.createSheet("变化明细");header(book,detail,List.of("物流商","渠道","国家","起重KG","止重KG","类型","字段","原值","新值","差额","涨跌百分比","影响","原文件","工作表","行号","分区"));
            var issues=book.createSheet("问题清单");header(book,issues,List.of("物流商","渠道","字段","原因","原表行","级别"));
            int sr=2,dr=1,ir=1;
            for(var file:batchPayload.path("fileReports"))if(file.path("status").asText().equals("failed"))textRow(issues,ir++,List.of("",file.path("fileName").asText(),"文件",file.path("message").asText(),"","error"));
            for(var item:batchPayload.path("results"))if(item.path("status").asText().equals("blocked")&&item.path("versionId").asText().isBlank())textRow(issues,ir++,List.of(item.path("providerName").asText(),item.path("channelName").asText(),"导入",item.path("message").asText(),"","error"));
            for(var id:ids) {
                var v=jdbc.sql("select v.payload::text,p.payload->>'name' as provider,c.payload->>'name' as channel from logistics_version v join logistics_channel c on c.id=v.channel_id join logistics_provider p on p.id=c.provider_id where v.id=:id")
                        .param("id",id).query((rs,n)->mapper.createObjectNode().put("provider",rs.getString("provider")).put("channel",rs.getString("channel")).set("version",mapper.readTree(rs.getString(1)))).optional().orElseThrow(()->AppException.notFound("版本不存在"));
                var version=(ObjectNode)v.path("version");
                for(var result:batchPayload.path("results"))if(result.path("versionId").asText().equals(id.toString())&&result.path("status").asText().equals("unchanged")){
                    version=version.deepCopy().put("status","unchanged").put("basePublishedVersionId",id.toString());
                    version.putObject("summary").put("added",0).put("price",0).put("rule",0).put("range",0).put("removed",0).put("coverageReduced",0).put("unchanged",version.path("rows").size());
                    var diffs=version.putArray("diffRows");for(var price:version.path("rows")){var diff=diffs.addObject().put("type","unchanged");diff.set("row",price);diff.set("previous",price);diff.putArray("changes");diff.putArray("kinds").add("unchanged");}
                    v.set("version",version);break;
                }
                var totals=version.path("summary");
                textRow(summary,sr++,List.of(v.path("provider").asText(),v.path("channel").asText(),version.path("versionNumber").asText(),version.path("basePublishedVersionId").asText(),totals.path("added").asText(),totals.path("price").asText(),totals.path("rule").asText(),totals.path("range").asText(),totals.path("removed").asText(),totals.path("coverageReduced").asText(),totals.path("unchanged").asText(),version.path("status").asText()));
                for(var diff:version.path("diffRows")) {
                    var changes=diff.path("changes");
                    if(changes.isEmpty()) {
                        var one=mapper.createObjectNode().put("field","完整价格行");one.set("before",diff.path("previous"));one.set("after",diff.path("type").asText().equals("removed")?mapper.nullNode():diff.path("row"));
                        var row=detail.createRow(dr++);diffRow(row,v,diff,one);
                    } else for(var change:changes)diffRow(detail.createRow(dr++),v,diff,change);
                }
                for(var issue:version.path("issues"))textRow(issues,ir++,List.of(v.path("provider").asText(),v.path("channel").asText(),issue.path("field").asText(),issue.path("message").asText(),issue.path("row").asText(),issue.path("level").asText()));
            }
            finish(book);book.write(bytes);return bytes.toByteArray();
        }catch(AppException e){throw e;}catch(Exception e){throw new IllegalStateException("差异Excel生成失败",e);}
    }
    private void diffRow(Row r,JsonNode v,JsonNode diff,JsonNode change){
        var source=diff.path("row");int c=0;cell(r,c++,v.path("provider"));cell(r,c++,v.path("channel"));cell(r,c++,source.path("areaName"));
        cell(r,c++,source.path("weightFromKg"));cell(r,c++,source.path("weightToKg"));cell(r,c++,mapper.getNodeFactory().textNode(typeLabels(diff)));cell(r,c++,change.path("field"));
        cell(r,c++,change.path("before"));cell(r,c++,change.path("after"));cell(r,c++,change.path("delta"));cell(r,c++,change.path("percentChange"));
        cell(r,c++,mapper.getNodeFactory().textNode(impact(diff,change)));
        cell(r,c++,v.path("version").path("fileName"));cell(r,c++,source.path("sourceSheet"));cell(r,c++,source.path("sourceRow"));cell(r,c,source.path("zoneName"));
    }
    private static String typeLabel(String type){return switch(type){case "added"->"新增";case "price"->"调价";case "rule"->"规则变化";case "range"->"重量区间变化";case "removed"->"移除";case "unchanged"->"无变化";default->type;};}
    private static String typeLabels(JsonNode diff){if(!diff.path("kinds").isArray()||diff.path("kinds").isEmpty())return typeLabel(diff.path("type").asText());var labels=new ArrayList<String>();for(var kind:diff.path("kinds"))labels.add(typeLabel(kind.asText()));return String.join(" / ",labels);}
    private static String impact(JsonNode diff,JsonNode change){
        var type=diff.path("type").asText();if(type.equals("added"))return "新增覆盖范围";if(type.equals("removed"))return "停止覆盖";
        var kind=change.path("kind").asText();if(kind.equals("range"))return rangeImpact(diff.path("previous"),diff.path("row"));
        if(kind.equals("price")||change.path("price").asBoolean(false)){
            var delta=change.path("delta");if(!delta.isNumber())return "价格已变化";
            var amount=(delta.asDouble()>0?"+":"")+String.format(Locale.ROOT,"%.2f",delta.asDouble());var percent=change.path("percentChange");
            return amount+(percent.isNumber()?" · "+(percent.asDouble()>0?"+":"")+String.format(Locale.ROOT,"%.2f%%",percent.asDouble()):"");
        }
        if(kind.equals("rule"))return "需复核计费规则";if(type.equals("range"))return rangeImpact(diff.path("previous"),diff.path("row"));
        return type.equals("rule")?"需复核计费规则":"无变化";
    }
    private static String rangeImpact(JsonNode before,JsonNode after){
        if(before.isMissingNode()||before.isNull())return "重量区间边界调整";
        var from=after.path("weightFromKg").asDouble()-before.path("weightFromKg").asDouble();var to=after.path("weightToKg").asDouble()-before.path("weightToKg").asDouble();
        if(from<=0&&to>=0&&(from<0||to>0))return "覆盖范围扩大";if(from>=0&&to<=0&&(from>0||to<0))return "覆盖范围缩小";
        if(from>0&&to>0)return "重量区间整体上移";if(from<0&&to<0)return "重量区间整体下移";return "重量区间边界调整";
    }
    private static void header(Workbook book,Sheet sheet,List<String> labels){header(book,sheet,0,labels);}
    private static void header(Workbook book,Sheet sheet,int rowNumber,List<String> labels){
        var style=book.createCellStyle();style.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());style.setFillPattern(FillPatternType.SOLID_FOREGROUND);style.setWrapText(true);
        var font=book.createFont();font.setColor(IndexedColors.WHITE.getIndex());font.setBold(true);style.setFont(font);
        var row=sheet.createRow(rowNumber);row.setHeightInPoints(36);for(int i=0;i<labels.size();i++){row.createCell(i).setCellValue(labels.get(i));row.getCell(i).setCellStyle(style);}
    }
    private static void textRow(Sheet s,int n,List<String> values){var r=s.createRow(n);for(int i=0;i<values.size();i++)r.createCell(i).setCellValue(truncate(values.get(i)));}
    private static void cell(Row r,int col,JsonNode value){var cell=r.createCell(col);if(value.isNumber())cell.setCellValue(value.asDouble());else if(value.isBoolean())cell.setCellValue(value.asBoolean()?"是":"否");else cell.setCellValue(truncate(value.isObject()||value.isArray()?value.toString():value.asText("")));}
    private static void stringCell(Row r,int col,String value){r.createCell(col,CellType.STRING).setCellValue(truncate(value==null?"":value));}
    private static String truncate(String value){if(value.length()>32767)throw AppException.unprocessable("单元格内容超过Excel限制，无法无损导出，请按渠道筛选或查看原文件");return value;}
    private static void finish(Workbook book){for(var s:book){s.setDisplayGridlines(false);if(s.getRow(0)!=null)for(int c=0;c<s.getRow(0).getLastCellNum();c++)s.setColumnWidth(c,18*256);if(s.getSheetName().equals("规则说明"))s.setColumnWidth(2,80*256);s.createFreezePane(0,1);}}
    private static void finishReview(Workbook book){for(var sheet:book){sheet.setDisplayGridlines(false);var header=sheet.getRow(1);if(header!=null)for(int c=0;c<header.getLastCellNum();c++)sheet.setColumnWidth(c,(c==22||c==23||c==24?28:18)*256);}}
}
