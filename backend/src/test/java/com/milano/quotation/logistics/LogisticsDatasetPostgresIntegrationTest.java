package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker=true)
class LogisticsDatasetPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    static JdbcClient jdbc;static TransactionTemplate tx;static final ObjectMapper mapper=new ObjectMapper();
    static LogisticsDatasetService datasets;static LogisticsDatasetGuard guard;static LogisticsQueryService queries;
    static LogisticsExportService exports;static LogisticsSourceParser parser;static AssetStorageService storage;static DataSourceTransactionManager transactions;
    @BeforeAll static void setup()throws Exception {
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration").load().migrate();
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());jdbc=JdbcClient.create(ds);transactions=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(transactions);
        storage=mock(AssetStorageService.class);var objects=new HashMap<String,byte[]>();
        doAnswer(i->{objects.put(i.getArgument(0),((InputStream)i.getArgument(1)).readAllBytes());return null;}).when(storage).putRaw(anyString(),any(),anyLong(),anyString());
        when(storage.openRaw(anyString())).thenAnswer(i->new ByteArrayInputStream(objects.get(i.getArgument(0))));
        guard=new LogisticsDatasetGuard(jdbc);datasets=new LogisticsDatasetService(jdbc,mapper,guard,storage);queries=new LogisticsQueryService(jdbc,mapper);exports=new LogisticsExportService(jdbc,mapper);parser=new LogisticsSourceParser(mapper,new LogisticsWorkbookService(mapper));
    }
    @Test void preparesThenSwitchesAtomicallyAndPreservesHistoricalSnapshots(){tx.executeWithoutResult(s->{try{
        var old=guard.activeId();var oldChannel=seed(old,"示例物流",true);var next=UUID.fromString(datasets.create("新库","QA").path("id").asText());var fresh=seed(next,"示例物流",true);
        var oldKey=key(oldChannel);var newKey=key(fresh);
        var policies=mapper.createArrayNode();policies.addObject().put("enabled",true).put("category","普货").putArray("countryRules").addObject().put("country","美国").putArray("allowedChannels").add(oldKey).add("unmatched-do-not-widen");
        jdbc.sql("update finance_setting set payload=cast(:p as jsonb),version=version+1 where setting_key='channel-policies'").param("p",policies.toString()).update();
        var template=mapper.createObjectNode().put("name","保持模板");template.putArray("selections").addObject().put("channelKey",oldKey).put("country","美国");
        jdbc.sql("insert into quotation_template values(:id,'QA','保持模板',cast(:p as jsonb),0,now(),now())").param("id",UUID.randomUUID()).param("p",template.toString()).update();
        jdbc.sql("insert into quotation_record values(:id,'QA-OLD','QA','pending','{\"amount\":123,\"channel\":\"旧快照\"}',0,now(),now())").param("id",UUID.randomUUID()).update();
        jdbc.sql("insert into quotation_draft values('QA',cast(:p as jsonb),0,now())").param("p",template.toString()).update();
        assertEquals(old,guard.activeId());assertEquals(1,queries.manifest().publishedChannels());
        var backup=datasets.backup(next,"QA");
        try(var stream=storage.openRaw(backup.path("objectKey").asText())){var snapshot=mapper.readTree(stream.readAllBytes());assertEquals(1,snapshot.path("billingAcceptances").size());assertTrue(snapshot.path("requiredRevisions").isArray());}catch(IOException e){throw new AssertionError(e);}
        var preview=datasets.preview(next,mapper.createArrayNode());assertEquals(1,preview.path("readyChannels").asInt());
        assertEquals(3,preview.path("bindingChanges").size());assertEquals(1,preview.path("draftsToReprice").asInt());
        var input=preview.deepCopy().put("note","QA确认").put("reviewConfirmed",true).put("unavailableConfirmed",true);
        datasets.activate(next,input,"QA");assertEquals(next,guard.activeId());assertEquals(1,queries.manifest().publishedChannels());assertEquals("archived",datasets.dataset(old).path("status").asText());
        assertThrows(AppException.class,()->guard.channel(oldChannel));
        var updated=mapper.readTree(jdbc.sql("select payload::text from finance_setting where setting_key='channel-policies'").query(String.class).single());
        assertEquals(newKey,updated.get(0).path("countryRules").get(0).path("allowedChannels").get(0).asText());assertEquals("unmatched-do-not-widen",updated.get(0).path("countryRules").get(0).path("allowedChannels").get(1).asText());
        assertTrue(jdbc.sql("select payload::text from quotation_template").query(String.class).single().contains(newKey));
        assertTrue(jdbc.sql("select payload::text from quotation_draft").query(String.class).single().contains(oldKey));assertEquals(123,jdbc.sql("select (payload->>'amount')::int from quotation_record").query(Integer.class).single());
        var quotation=mapper.createObjectNode().put("logisticsRevision",queries.manifest().revision()).put("logisticsAttribute","普货");quotation.putArray("quoteOptions").addObject().put("country","美国").put("channelKey",oldKey);
        var validator=new LogisticsQuotationGuard(jdbc,queries,mapper);assertThrows(AppException.class,()->validator.validate(quotation));var option=(ObjectNode)quotation.path("quoteOptions").get(0);option.put("channelKey",newKey).put("logisticsChannelId",fresh.toString()).put("logisticsVersionId",jdbc.sql("select current_version_id from logistics_channel where id=:id").param("id",fresh).query(UUID.class).single().toString()).put("freightCny",45);option.putObject("logisticsInput").put("country","美国").put("weightKg",.5);validator.validate(quotation);assertEquals(next.toString(),quotation.path("logisticsDatasetId").asText());
    }finally{s.setRollbackOnly();}});}
    @Test void stalePreviewCannotSwitchAndPendingPriceNeverEntersQuotation(){tx.executeWithoutResult(s->{try{
        var old=guard.activeId();seed(old,"旧物流",true);var next=UUID.fromString(datasets.create("新库","QA").path("id").asText());seed(next,"新物流",true);seed(next,"待适配物流",false);
        datasets.backup(next,"QA");var input=datasets.preview(next,mapper.createArrayNode()).put("note","QA").put("reviewConfirmed",true).put("unavailableConfirmed",true);
        seed(next,"新增物流",true);assertThrows(AppException.class,()->datasets.activate(next,input,"QA"));assertEquals(old,guard.activeId());
        var latest=datasets.preview(next,mapper.createArrayNode()).put("note","QA").put("reviewConfirmed",true).put("unavailableConfirmed",true);datasets.activate(next,latest,"QA");assertEquals(2,queries.manifest().publishedChannels());assertEquals(3,datasets.prices(next,0,50,"","","").total());
    }finally{s.setRollbackOnly();}});}
    @Test void pricesReturnFilteredTotalsAndStableEmptyPages(){tx.executeWithoutResult(s->{try{
        var dataset=guard.activeId();seed(dataset,"分页物流甲",true);seed(dataset,"分页物流乙",true);
        var first=datasets.prices(dataset,0,1,"分页物流","US","普货");
        assertEquals(2,first.total());assertEquals(2,first.totalPages());assertEquals(1,first.items().size());
        var second=datasets.prices(dataset,1,1,"分页物流","美国","普货");
        assertEquals(2,second.total());assertEquals(1,second.items().size());assertNotEquals(first.items().get(0).path("channelId"),second.items().get(0).path("channelId"));
        var beyond=datasets.prices(dataset,9,1,"分页物流","US","普货");
        assertEquals(2,beyond.total());assertEquals(0,beyond.items().size());
        var empty=datasets.prices(dataset,0,20,"不存在的渠道","US","普货");
        assertEquals(0,empty.total());assertEquals(0,empty.totalPages());assertEquals(0,empty.items().size());
    }finally{s.setRollbackOnly();}});}
    @Test void quotationRejectsStaleVersionsTamperedFreightAndUnacceptedPrices(){tx.executeWithoutResult(s->{try{
        var c=seed(guard.activeId(),"服务端计费测试",true);var v=jdbc.sql("select current_version_id from logistics_channel where id=:id").param("id",c).query(UUID.class).single();
        var policies=mapper.createArrayNode();policies.addObject().put("enabled",true).put("category","普货").putArray("countryRules").addObject().put("country","美国").putArray("allowedChannels").add(key(c));
        jdbc.sql("update finance_setting set payload=cast(:p as jsonb) where setting_key='channel-policies'").param("p",policies.toString()).update();
        var q=mapper.createObjectNode().put("logisticsRevision",queries.manifest().revision()).put("logisticsAttribute","普货");var o=q.putArray("quoteOptions").addObject().put("channelKey",key(c)).put("country","美国").put("logisticsChannelId",c.toString()).put("logisticsVersionId",v.toString()).put("freightCny",45);
        o.putObject("logisticsInput").put("country","美国").put("weightKg",.5);var validator=new LogisticsQuotationGuard(jdbc,queries,mapper);validator.validate(q);
        o.put("freightCny",1).put("quoteReady",true);assertThrows(AppException.class,()->validator.validate(q));o.put("freightCny",45);
        o.put("logisticsVersionId",UUID.randomUUID().toString());assertThrows(AppException.class,()->validator.validate(q));o.put("logisticsVersionId",v.toString());
        ((ObjectNode)o.path("logisticsInput")).put("country","德国");assertThrows(AppException.class,()->validator.validate(q));((ObjectNode)o.path("logisticsInput")).put("country","美国");
        q.put("logisticsRevision","old-page");assertThrows(AppException.class,()->validator.validate(q));q.put("logisticsRevision",queries.manifest().revision());
        jdbc.sql("update logistics_billing_acceptance set engine_version='obsolete' where version_id=:id").param("id",v).update();q.put("logisticsRevision",queries.manifest().revision());assertThrows(AppException.class,()->validator.validate(q));
    }finally{s.setRollbackOnly();}});}
    @Test void exportIncludesAllPagesAndCanReimportWithoutChangingBusinessContent(){tx.executeWithoutResult(s->{try{
        var dataset=guard.activeId();seed(dataset,"示例物流",true);
        var parsed=parser.parse(exports.prices(dataset,null,"示例","US","普货"),"标准.xlsx");assertEquals(1,parsed.path("channels").size());assertEquals(0,parsed.path("channels").get(0).path("errors").asInt());
        var original=mapper.readTree(jdbc.sql("select payload::text from logistics_version").query(String.class).single());
        assertEquals(parser.businessHash((ArrayNode)original.path("rows")),parsed.path("channels").get(0).path("contentHash").asText());
    }finally{s.setRollbackOnly();}});}
    @Test void nativeDownloadSnapshotRejectsChangedPricesOrFilters(){tx.executeWithoutResult(s->{try{
        var dataset=guard.activeId();var c=seed(dataset,"固定下载",true);
        var token=exports.priceSnapshot(dataset,null,"固定","US","普货");
        assertTrue(exports.prices(dataset,null,"固定","US","普货",token).length>0);
        assertThrows(AppException.class,()->exports.prices(dataset,null,"固定","GB","普货",token));
        jdbc.sql("update logistics_version set payload=jsonb_set(payload,'{rows,0,pricePerKg}','99'::jsonb) where channel_id=:c").param("c",c).update();
        assertThrows(AppException.class,()->exports.prices(dataset,null,"固定","US","普货",token));
        var empty=UUID.fromString(datasets.create("空测试库","QA").path("id").asText());
        assertThrows(AppException.class,()->exports.priceSnapshot(empty,null,"","",""));
        assertThrows(AppException.class,()->exports.priceSnapshot(empty,jdbc.sql("select current_version_id from logistics_channel where id=:c").param("c",c).query(UUID.class).single(),"","",""));
    }finally{s.setRollbackOnly();}});}
    @Test void standardizedReviewExportIsTraceableNonImportableAndSnapshotProtected(){tx.executeWithoutResult(s->{try{
        var dataset=guard.activeId();var channel=seed(dataset,"关键字段物流",false);var version=jdbc.sql("select current_version_id from logistics_channel where id=:c").param("c",channel).query(UUID.class).single();
        var payload=(ObjectNode)mapper.readTree(jdbc.sql("select payload::text from logistics_version where id=:v").param("v",version).query(String.class).single());
        var row=(ObjectNode)payload.path("rows").get(0);row.put("sourceProductCode","=HYPERLINK(\"https://invalid.example\")").put("sourceFeeLabel","操作费/票").put("sourceFile","原始报价.xlsx").put("sourceSheet","价格表").put("sourceRow",9);
        jdbc.sql("update logistics_version set payload=cast(:p as jsonb) where id=:v").param("p",payload.toString()).param("v",version).update();
        var token=exports.standardizedSnapshot(null,version);var bytes=exports.standardized(null,version,token);
        try(var report=new XSSFWorkbook(new ByteArrayInputStream(bytes))){
            assertEquals(List.of("关键字段","待补时效","问题清单"),java.util.stream.IntStream.range(0,report.getNumberOfSheets()).mapToObj(i->report.getSheetAt(i).getSheetName()).toList());
            assertEquals("MILANO_LOGISTICS_REVIEW_V1",report.getSheet("关键字段").getRow(0).getCell(0).getStringCellValue());
            assertEquals("物流商",report.getSheet("关键字段").getRow(1).getCell(0).getStringCellValue());
            assertEquals(CellType.STRING,report.getSheet("关键字段").getRow(2).getCell(2).getCellType());
            assertTrue(report.getSheet("关键字段").getRow(2).getCell(2).getStringCellValue().startsWith("="));
            assertEquals("缺失",report.getSheet("待补时效").getRow(2).getCell(2).getStringCellValue());
        }
        assertThrows(AppException.class,()->parser.parse(bytes,"审核导出.xlsx"));
        jdbc.sql("update logistics_version set payload=jsonb_set(payload,'{rows,0,pricePerKg}','99'::jsonb) where id=:v").param("v",version).update();
        assertThrows(AppException.class,()->exports.standardized(null,version,token));
    }catch(IOException e){throw new AssertionError(e);}finally{s.setRollbackOnly();}});}
    @Test void unchangedBatchDiffDoesNotReuseHistoricalVersionAdditions(){tx.executeWithoutResult(s->{try{
        var dataset=guard.activeId();var c=seed(dataset,"批次报表",false);
        var v=jdbc.sql("select current_version_id from logistics_channel where id=:c").param("c",c).query(UUID.class).single();
        var payload=(ObjectNode)mapper.readTree(jdbc.sql("select payload::text from logistics_version where id=:v").param("v",v).query(String.class).single());
        ((ObjectNode)payload.path("rows").get(0)).put("zoneName","2区");payload.putObject("summary").put("added",1).put("unchanged",0);
        payload.putArray("diffRows").addObject().put("type","added").set("row",payload.path("rows").get(0));
        jdbc.sql("update logistics_version set payload=cast(:p as jsonb) where id=:v").param("p",payload.toString()).param("v",v).update();
        var b=UUID.randomUUID();var batch=mapper.createObjectNode();batch.putArray("results").addObject().put("versionId",v.toString()).put("status","unchanged");
        jdbc.sql("insert into logistics_import_batch(id,dataset_id,requested_by,request_key,status,phase,payload) values(:id,:d,'QA',:key,'completed','review',cast(:p as jsonb))").param("id",b).param("d",dataset).param("key",b.toString()).param("p",batch.toString()).update();
        try(var report=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(exports.changes(b,null)))){
            var row=report.getSheet("批次汇总").getRow(2);assertEquals("0",row.getCell(4).getStringCellValue());assertEquals("1",row.getCell(10).getStringCellValue());assertEquals(v.toString(),row.getCell(3).getStringCellValue());assertEquals("unchanged",row.getCell(11).getStringCellValue());
            var detail=report.getSheet("变化明细").getRow(1);assertEquals("无变化",detail.getCell(5).getStringCellValue());assertEquals(detail.getCell(7).getStringCellValue(),detail.getCell(8).getStringCellValue());assertEquals("2区",detail.getCell(15).getStringCellValue());
        }
        try(var original=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(exports.changes(null,v)))){assertEquals("1",original.getSheet("批次汇总").getRow(2).getCell(4).getStringCellValue());assertEquals("新增",original.getSheet("变化明细").getRow(1).getCell(5).getStringCellValue());}
    }catch(IOException e){throw new AssertionError(e);}finally{s.setRollbackOnly();}});}
    @Test void durableWorkerStagesAChannelInsideTheDatabaseTransaction(){tx.executeWithoutResult(s->{try{
        var active=guard.activeId();seed(active,"验收源",true);var bytes=exports.prices(active,null,"验收源","","");
        var dataset=UUID.fromString(datasets.create("导入准备区","QA").path("id").asText());
        var id=UUID.randomUUID();var objectKey="qa/"+id;
        storage.putRaw(objectKey,new ByteArrayInputStream(bytes),bytes.length,"application/octet-stream");
        var payload=mapper.createObjectNode();payload.putArray("files").addObject().put("name","标准.xlsx").put("objectKey",objectKey).put("sha256",AssetStorageService.sha256(bytes));
        jdbc.sql("insert into logistics_import_batch(id,dataset_id,requested_by,request_key,status,phase,payload) values(:id,:dataset,'QA',:key,'queued','queued',cast(:payload as jsonb))")
                .param("id",id).param("dataset",dataset).param("key",id.toString()).param("payload",payload.toString()).update();
        var logistics=mock(LogisticsService.class);
        when(logistics.createDraft(any(),any())).thenAnswer(i->((ObjectNode)i.getArgument(1)).deepCopy().put("id",UUID.randomUUID().toString()).put("versionNumber",1));
        var worker=new LogisticsImportService(jdbc,mapper,storage,parser,logistics,guard,transactions);
        try {
            worker.process(id);var batch=worker.get(id);assertEquals("completed",batch.path("status").asText());
            var report=batch.path("payload").path("fileReports").get(0);var evidenceKey=report.path("sourceEvidence").path("objectKey").asText();assertFalse(evidenceKey.isBlank());
            assertFalse(report.path("sheets").get(0).has("sourceCells"));
            try(var raw=storage.openRaw(evidenceKey)){var evidence=mapper.readTree(raw.readAllBytes());assertTrue(evidence.path("sheets").get(0).path("sourceCells").isArray());}catch(IOException e){throw new AssertionError(e);}
            assertEquals("draft",batch.path("payload").path("results").get(0).path("status").asText(),batch.toString());
            assertEquals(1,datasets.workspace(dataset).path("channels").size());verify(logistics).createDraft(any(),any());
            worker.process(id);verifyNoMoreInteractions(logistics);
        }finally{worker.close();}
    }finally{s.setRollbackOnly();}});}
    @Test void failedSourceRetentionUsesAPostgresCompatibleTimestamp(){tx.executeWithoutResult(s->{try{
        var dataset=guard.activeId();var batch=UUID.randomUUID();var requestKey=batch.toString();
        jdbc.sql("insert into logistics_import_batch(id,dataset_id,requested_by,request_key,status,phase,payload) values(:id,:dataset,'QA',:key,'processing','parsing','{}'::jsonb)")
                .param("id",batch).param("dataset",dataset).param("key",requestKey).update();
        jdbc.sql("insert into logistics_import_file(batch_id,file_index,original_name,object_key,sha256,size_bytes,status) values(:batch,0,'待适配.xlsx','qa/pending',:sha,1,'stored')")
                .param("batch",batch).param("sha","0".repeat(64)).update();
        var worker=new LogisticsImportService(jdbc,mapper,storage,parser,mock(LogisticsService.class),guard,transactions);
        try {
            var until=java.time.Instant.parse("2026-09-11T06:29:10Z");
            assertDoesNotThrow(()->worker.markFailed(batch,0,"新模板待适配",until));
            var state=jdbc.sql("select concat(status,'|',delete_error,'|',retention_until is not null) from logistics_import_file where batch_id=:batch and file_index=0")
                    .param("batch",batch).query(String.class).single();
            assertEquals("failed|新模板待适配|t",state);
        }finally{worker.close();}
    }finally{s.setRollbackOnly();}});}
    @Test void oneUnsupportedFileDoesNotAbortTheRemainingBatch(){tx.executeWithoutResult(s->{try{
        var active=guard.activeId();seed(active,"批次继续解析",true);var supported=exports.prices(active,null,"批次继续解析","","");
        var unsupported="not-an-excel-workbook".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var dataset=UUID.fromString(datasets.create("混合文件导入准备区","QA").path("id").asText());var batch=UUID.randomUUID();
        var firstKey="qa/"+batch+"/unsupported";var secondKey="qa/"+batch+"/supported";
        storage.putRaw(firstKey,new ByteArrayInputStream(unsupported),unsupported.length,"application/octet-stream");
        storage.putRaw(secondKey,new ByteArrayInputStream(supported),supported.length,"application/octet-stream");
        var payload=mapper.createObjectNode();var files=payload.putArray("files");
        files.addObject().put("name","待适配.xlsx").put("objectKey",firstKey).put("sha256",AssetStorageService.sha256(unsupported));
        files.addObject().put("name","标准.xlsx").put("objectKey",secondKey).put("sha256",AssetStorageService.sha256(supported));
        jdbc.sql("insert into logistics_import_batch(id,dataset_id,requested_by,request_key,status,phase,payload) values(:id,:dataset,'QA',:key,'queued','queued',cast(:payload as jsonb))")
                .param("id",batch).param("dataset",dataset).param("key",batch.toString()).param("payload",payload.toString()).update();
        for(int index=0;index<2;index++){var file=files.get(index);jdbc.sql("insert into logistics_import_file(batch_id,file_index,original_name,object_key,sha256,size_bytes,status) values(:batch,:index,:name,:key,:sha,:size,'stored')")
                .param("batch",batch).param("index",index).param("name",file.path("name").asText()).param("key",file.path("objectKey").asText()).param("sha",file.path("sha256").asText()).param("size",index==0?unsupported.length:supported.length).update();}
        var logistics=mock(LogisticsService.class);when(logistics.createDraft(any(),any())).thenAnswer(i->((ObjectNode)i.getArgument(1)).deepCopy().put("id",UUID.randomUUID().toString()).put("versionNumber",1));
        var worker=new LogisticsImportService(jdbc,mapper,storage,parser,logistics,guard,transactions);
        try {
            worker.process(batch);var result=worker.get(batch);assertEquals("completed",result.path("status").asText(),result.toString());
            assertEquals("failed",result.path("payload").path("fileReports").get(0).path("status").asText());
            assertEquals("parsed",result.path("payload").path("fileReports").get(1).path("status").asText());
            assertEquals(1,result.path("payload").path("results").size());verify(logistics).createDraft(any(),any());
        }finally{worker.close();}
    }finally{s.setRollbackOnly();}});}
    @Test void startupRecoveryRequeuesAStaleLeaseAndDuplicateDispatchCannotDoubleClaim()throws Exception{
        var dataset=guard.activeId();var batch=UUID.randomUUID();var payload=mapper.createObjectNode();payload.putArray("files");payload.putArray("results");
        jdbc.sql("insert into logistics_import_batch(id,dataset_id,requested_by,request_key,status,phase,payload,lease_id,updated_at) values(:id,:dataset,'QA',:key,'processing','parsing',cast(:payload as jsonb),:lease,now()-interval '16 minutes')")
                .param("id",batch).param("dataset",dataset).param("key",batch.toString()).param("payload",payload.toString()).param("lease",UUID.randomUUID()).update();
        var worker=new LogisticsImportService(jdbc,mapper,storage,parser,mock(LogisticsService.class),guard,transactions);ReflectionTestUtils.setField(worker,"resumeOnStart",true);
        try{
            worker.resumeQueued();worker.dispatchQueued();
            var deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);String status;
            do{status=worker.get(batch).path("status").asText();if(!Set.of("queued","processing").contains(status))break;Thread.sleep(20);}while(System.nanoTime()<deadline);
            assertEquals("failed",status);assertTrue(jdbc.sql("select updated_at>now()-interval '1 minute' from logistics_import_batch where id=:id").param("id",batch).query(Boolean.class).single());
        }finally{worker.close();jdbc.sql("delete from logistics_import_batch where id=:id").param("id",batch).update();}
    }
    @Test void allFirstNextImportCompletesWithoutDraftsOrPublicationChecks(){tx.executeWithoutResult(s->{try(var book=new XSSFWorkbook()){
        var sheet=book.createSheet("首续重");LogisticsSourceParserTest.row(sheet,0,"国家","重量段","首重0.5kg","续重0.5kg");
        LogisticsSourceParserTest.row(sheet,1,"美国","0-2","无效首重价","无效续重价");
        var bytes=LogisticsSourceParserTest.bytes(book);var id=UUID.randomUUID();var key="qa/"+id;
        storage.putRaw(key,new ByteArrayInputStream(bytes),bytes.length,"application/octet-stream");
        var payload=mapper.createObjectNode();payload.putArray("files").addObject().put("name","花海.xlsx").put("objectKey",key).put("sha256",AssetStorageService.sha256(bytes));
        jdbc.sql("insert into logistics_import_batch(id,dataset_id,requested_by,request_key,status,phase,payload) values(:id,:d,'QA',:key,'queued','queued',cast(:p as jsonb))")
            .param("id",id).param("d",guard.activeId()).param("key",id.toString()).param("p",payload.toString()).update();
        var logistics=mock(LogisticsService.class);var worker=new LogisticsImportService(jdbc,mapper,storage,parser,logistics,guard,transactions);
        try {worker.process(id);var result=worker.get(id);assertEquals("completed",result.path("status").asText());
            assertTrue(result.path("payload").path("results").isEmpty());assertEquals("filtered",result.path("payload").path("fileReports").get(0).path("status").asText());
            verifyNoInteractions(logistics);
        }finally{worker.close();}
    }catch(Exception e){throw new AssertionError(e);}finally{s.setRollbackOnly();}});}

    static UUID seed(UUID dataset,String name,boolean ready){
        var p=UUID.randomUUID();var c=UUID.randomUUID();var v=UUID.randomUUID();var code="SAME-"+name;int rule=guard.nextRuleId();
        var row=mapper.createObjectNode().put("areaName","美国").put("countryCode","US").put("weightFromKg",0).put("weightToKg",1).put("weightFromInclusive",false).put("weightToInclusive",true).put("pricePerKg",50).put("registrationFee",20).put("currency","CNY").put("pricingModel","per-kg").put("originRegion","").put("notes","").put("pendingReason","").put("quoteReady",ready).put("billingStepKg",0).put("linehaulPerKg",0);
        for(int i=0;i<LogisticsWorkbookService.KEYS.length;i++)if(!row.has(LogisticsWorkbookService.KEYS[i])){String k=LogisticsWorkbookService.KEYS[i];if(Set.of(0,1,4,5,28,32,33,34,35,36).contains(i))row.put(k,"");else if(Set.of(29,30,31,37).contains(i))row.put(k,i==29);else row.put(k,0);}
        row.put("volumetric",false);row.put("rowKey",LogisticsDatasetService.hash("US|美国|||0.0|1.0|false|true"));
        var payload=mapper.createObjectNode().put("id",v.toString()).put("versionNumber",1).put("status","published").put("quoteReady",ready);payload.putArray("rows").add(row);payload.put("contentHash",parser.businessHash((ArrayNode)payload.path("rows")));
        jdbc.sql("insert into logistics_provider(id,dataset_id,code,payload,created_at,updated_at) values(:id,:d,:code,cast(:p as jsonb),now(),now())").param("id",p).param("d",dataset).param("code",code).param("p",mapper.createObjectNode().put("name",name).put("enabled",true).toString()).update();
        jdbc.sql("insert into logistics_channel(id,dataset_id,provider_id,code,rule_id,payload,created_at,updated_at) values(:id,:d,:p,:code,:rule,cast(:payload as jsonb),now(),now())").param("id",c).param("d",dataset).param("p",p).param("code",code).param("rule",rule).param("payload",mapper.createObjectNode().put("name","普货渠道").put("enabled",true).put("logisticsAttribute","普货").toString()).update();
        jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at) values(:id,:c,1,'published','hash',cast(:p as jsonb),now())").param("id",v).param("c",c).param("p",payload.toString()).update();jdbc.sql("update logistics_channel set current_version_id=:v where id=:c").param("v",v).param("c",c).update();
        if(ready){
            var billing=new LogisticsBillingAcceptanceService(jdbc,mapper,guard,new LogisticsBillingEngine(mapper));var approval=billing.status(v).put("reviewConfirmed",true).put("note","独立测试价50/kg+20/票").put("sourceReference","测试样本");var samples=approval.putArray("samples");
            for(double weight:List.of(.2,.8)){var sample=samples.addObject().put("expectedTotal",weight*50+20).put("sourceReference","50*重量+20");sample.set("input",LogisticsBillingEngineTest.input(weight));}
            samples.addObject().put("expectRejected",true).put("sourceReference","大于1kg不支持").set("input",LogisticsBillingEngineTest.input(2));billing.approve(v,approval,"QA");
            if(datasets.dataset(dataset).path("status").asText().equals("preparing")){var required=datasets.requiredChannels(dataset);((ArrayNode)required.path("channelIds")).add(c.toString());required.put("confirmed",true).put("note","测试明确选中必用渠道");datasets.saveRequiredChannels(dataset,required,"QA");}
        }
        return c;
    }
    static String key(UUID c){return jdbc.sql("select concat(c.rule_id,'::',p.payload->>'name','::',c.code) from logistics_channel c join logistics_provider p on p.id=c.provider_id where c.id=:id").param("id",c).query(String.class).single();}
}
