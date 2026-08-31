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
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public byte[] prices(UUID dataset,UUID versionId,String query,String country,String attribute) {
        var versions=jdbc.sql("""
                select v.payload::text as payload,p.payload->>'name' as provider,c.payload->>'name' as channel,
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
            textRow(summary,1,List.of("物流商","渠道","版本","基线版本","新增","调价","规则","移除","未变","状态"));
            var detail=book.createSheet("变化明细");header(book,detail,List.of("物流商","渠道","国家","起重KG","止重KG","类型","字段","原值","新值","差额","涨跌百分比","原文件","工作表","行号"));
            var issues=book.createSheet("问题清单");header(book,issues,List.of("物流商","渠道","字段","原因","原表行","级别"));
            int sr=2,dr=1,ir=1;
            for(var file:batchPayload.path("fileReports"))if(file.path("status").asText().equals("failed"))textRow(issues,ir++,List.of("",file.path("fileName").asText(),"文件",file.path("message").asText(),"","error"));
            for(var item:batchPayload.path("results"))if(item.path("status").asText().equals("blocked")&&item.path("versionId").asText().isBlank())textRow(issues,ir++,List.of(item.path("providerName").asText(),item.path("channelName").asText(),"导入",item.path("message").asText(),"","error"));
            for(var id:ids) {
                var v=jdbc.sql("select v.payload::text,p.payload->>'name' as provider,c.payload->>'name' as channel from logistics_version v join logistics_channel c on c.id=v.channel_id join logistics_provider p on p.id=c.provider_id where v.id=:id")
                        .param("id",id).query((rs,n)->mapper.createObjectNode().put("provider",rs.getString("provider")).put("channel",rs.getString("channel")).set("version",mapper.readTree(rs.getString(1)))).optional().orElseThrow(()->AppException.notFound("版本不存在"));
                var version=v.path("version");var totals=version.path("summary");
                textRow(summary,sr++,List.of(v.path("provider").asText(),v.path("channel").asText(),version.path("versionNumber").asText(),version.path("basePublishedVersionId").asText(),totals.path("added").asText(),totals.path("price").asText(),totals.path("rule").asText(),totals.path("removed").asText(),totals.path("unchanged").asText(),version.path("status").asText()));
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
        cell(r,c++,source.path("weightFromKg"));cell(r,c++,source.path("weightToKg"));cell(r,c++,diff.path("type"));cell(r,c++,change.path("field"));
        cell(r,c++,change.path("before"));cell(r,c++,change.path("after"));cell(r,c++,change.path("delta"));cell(r,c++,change.path("percentChange"));
        cell(r,c++,v.path("version").path("fileName"));cell(r,c++,source.path("sourceSheet"));cell(r,c,source.path("sourceRow"));
    }
    private static void header(Workbook book,Sheet sheet,List<String> labels){
        var style=book.createCellStyle();style.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());style.setFillPattern(FillPatternType.SOLID_FOREGROUND);style.setWrapText(true);
        var font=book.createFont();font.setColor(IndexedColors.WHITE.getIndex());font.setBold(true);style.setFont(font);
        var row=sheet.createRow(0);row.setHeightInPoints(36);for(int i=0;i<labels.size();i++){row.createCell(i).setCellValue(labels.get(i));row.getCell(i).setCellStyle(style);}
    }
    private static void textRow(Sheet s,int n,List<String> values){var r=s.createRow(n);for(int i=0;i<values.size();i++)r.createCell(i).setCellValue(truncate(values.get(i)));}
    private static void cell(Row r,int col,JsonNode value){var cell=r.createCell(col);if(value.isNumber())cell.setCellValue(value.asDouble());else if(value.isBoolean())cell.setCellValue(value.asBoolean()?"是":"否");else cell.setCellValue(truncate(value.isObject()||value.isArray()?value.toString():value.asText("")));}
    private static String truncate(String value){if(value.length()>32767)throw AppException.unprocessable("单元格内容超过Excel限制，无法无损导出，请按渠道筛选或查看原文件");return value;}
    private static void finish(Workbook book){for(var s:book){s.setDisplayGridlines(false);if(s.getRow(0)!=null)for(int c=0;c<s.getRow(0).getLastCellNum();c++)s.setColumnWidth(c,18*256);if(s.getSheetName().equals("规则说明"))s.setColumnWidth(2,80*256);s.createFreezePane(0,1);}}
}
