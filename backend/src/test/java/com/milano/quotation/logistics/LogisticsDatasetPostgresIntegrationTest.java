package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
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
        datasets.backup(next,"QA");var preview=datasets.preview(next,mapper.createArrayNode());assertEquals(1,preview.path("readyChannels").asInt());
        assertEquals(3,preview.path("bindingChanges").size());assertEquals(1,preview.path("draftsToReprice").asInt());
        var input=preview.deepCopy().put("note","QA确认").put("reviewConfirmed",true).put("unavailableConfirmed",true);
        datasets.activate(next,input,"QA");assertEquals(next,guard.activeId());assertEquals(1,queries.manifest().publishedChannels());assertEquals("archived",datasets.dataset(old).path("status").asText());
        assertThrows(AppException.class,()->guard.channel(oldChannel));
        var updated=mapper.readTree(jdbc.sql("select payload::text from finance_setting where setting_key='channel-policies'").query(String.class).single());
        assertEquals(newKey,updated.get(0).path("countryRules").get(0).path("allowedChannels").get(0).asText());assertEquals("unmatched-do-not-widen",updated.get(0).path("countryRules").get(0).path("allowedChannels").get(1).asText());
        assertTrue(jdbc.sql("select payload::text from quotation_template").query(String.class).single().contains(newKey));
        assertTrue(jdbc.sql("select payload::text from quotation_draft").query(String.class).single().contains(oldKey));assertEquals(123,jdbc.sql("select (payload->>'amount')::int from quotation_record").query(Integer.class).single());
        var quotation=mapper.createObjectNode().put("logisticsRevision",queries.manifest().revision()).put("logisticsAttribute","普货");quotation.putArray("quoteOptions").addObject().put("country","美国").put("channelKey",oldKey);
        var validator=new LogisticsQuotationGuard(jdbc,queries,mapper);assertThrows(AppException.class,()->validator.validate(quotation));((ObjectNode)quotation.path("quoteOptions").get(0)).put("channelKey",newKey);validator.validate(quotation);assertEquals(next.toString(),quotation.path("logisticsDatasetId").asText());
    }finally{s.setRollbackOnly();}});}
    @Test void stalePreviewCannotSwitchAndPendingPriceNeverEntersQuotation(){tx.executeWithoutResult(s->{try{
        var old=guard.activeId();seed(old,"旧物流",true);var next=UUID.fromString(datasets.create("新库","QA").path("id").asText());seed(next,"新物流",true);seed(next,"待适配物流",false);
        datasets.backup(next,"QA");var input=datasets.preview(next,mapper.createArrayNode()).put("note","QA").put("reviewConfirmed",true).put("unavailableConfirmed",true);
        seed(next,"新增物流",true);assertThrows(AppException.class,()->datasets.activate(next,input,"QA"));assertEquals(old,guard.activeId());
        var latest=datasets.preview(next,mapper.createArrayNode()).put("note","QA").put("reviewConfirmed",true).put("unavailableConfirmed",true);datasets.activate(next,latest,"QA");assertEquals(2,queries.manifest().publishedChannels());assertEquals(3,datasets.prices(next,0,50,"","","").total());
    }finally{s.setRollbackOnly();}});}
    @Test void exportIncludesAllPagesAndCanReimportWithoutChangingBusinessContent(){tx.executeWithoutResult(s->{try{
        var dataset=guard.activeId();seed(dataset,"示例物流",true);
        var parsed=parser.parse(exports.prices(dataset,null,"示例","US","普货"),"标准.xlsx");assertEquals(1,parsed.path("channels").size());assertEquals(0,parsed.path("channels").get(0).path("errors").asInt());
        var original=mapper.readTree(jdbc.sql("select payload::text from logistics_version").query(String.class).single());
        assertEquals(parser.businessHash((ArrayNode)original.path("rows")),parsed.path("channels").get(0).path("contentHash").asText());
    }finally{s.setRollbackOnly();}});}
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
            assertEquals("draft",batch.path("payload").path("results").get(0).path("status").asText(),batch.toString());
            assertEquals(1,datasets.workspace(dataset).path("channels").size());verify(logistics).createDraft(any(),any());
            worker.process(id);verifyNoMoreInteractions(logistics);
        }finally{worker.close();}
    }finally{s.setRollbackOnly();}});}
    static UUID seed(UUID dataset,String name,boolean ready){
        var p=UUID.randomUUID();var c=UUID.randomUUID();var v=UUID.randomUUID();var code="SAME-"+name;int rule=guard.nextRuleId();
        var row=mapper.createObjectNode().put("areaName","美国").put("countryCode","US").put("weightFromKg",0).put("weightToKg",1).put("weightFromInclusive",false).put("weightToInclusive",true).put("pricePerKg",50).put("registrationFee",20).put("currency","CNY").put("pricingModel","per-kg").put("originRegion","").put("notes","").put("pendingReason","").put("quoteReady",ready).put("billingStepKg",0).put("linehaulPerKg",0);
        for(int i=0;i<LogisticsWorkbookService.KEYS.length;i++)if(!row.has(LogisticsWorkbookService.KEYS[i])){String k=LogisticsWorkbookService.KEYS[i];if(Set.of(0,1,4,5,28,32,33,34,35,36).contains(i))row.put(k,"");else if(Set.of(29,30,31,37).contains(i))row.put(k,i==29);else row.put(k,0);}
        row.put("rowKey",LogisticsDatasetService.hash("US|美国|||0.0|1.0|false|true"));
        var payload=mapper.createObjectNode().put("id",v.toString()).put("versionNumber",1).put("status","published").put("quoteReady",ready);payload.putArray("rows").add(row);payload.put("contentHash",parser.businessHash((ArrayNode)payload.path("rows")));
        jdbc.sql("insert into logistics_provider(id,dataset_id,code,payload,created_at,updated_at) values(:id,:d,:code,cast(:p as jsonb),now(),now())").param("id",p).param("d",dataset).param("code",code).param("p",mapper.createObjectNode().put("name",name).put("enabled",true).toString()).update();
        jdbc.sql("insert into logistics_channel(id,dataset_id,provider_id,code,rule_id,payload,created_at,updated_at) values(:id,:d,:p,:code,:rule,cast(:payload as jsonb),now(),now())").param("id",c).param("d",dataset).param("p",p).param("code",code).param("rule",rule).param("payload",mapper.createObjectNode().put("name","普货渠道").put("enabled",true).put("logisticsAttribute","普货").toString()).update();
        jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at) values(:id,:c,1,'published','hash',cast(:p as jsonb),now())").param("id",v).param("c",c).param("p",payload.toString()).update();jdbc.sql("update logistics_channel set current_version_id=:v where id=:c").param("v",v).param("c",c).update();return c;
    }
    static String key(UUID c){return jdbc.sql("select concat(c.rule_id,'::',p.payload->>'name','::',c.code) from logistics_channel c join logistics_provider p on p.id=c.provider_id where c.id=:id").param("id",c).query(String.class).single();}
}
