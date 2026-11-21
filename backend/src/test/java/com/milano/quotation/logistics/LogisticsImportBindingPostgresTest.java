package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import com.milano.quotation.storage.AssetStorageService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.defer-datasource-initialization=false","spring.sql.init.mode=never","spring.session.store-type=none",
    "app.storage.initialize=false","app.logistics.resume-on-start=false","app.logistics.file-cleanup-enabled=false"})
@ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker=true)
class LogisticsImportBindingPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired JdbcClient jdbc; @Autowired ObjectMapper mapper; @Autowired LogisticsSourceParser parser;
    @Autowired LogisticsService logistics; @Autowired LogisticsDatasetGuard guard; @Autowired PlatformTransactionManager manager;
    @Autowired CompanyChannelService directory; @Autowired LogisticsBillingAcceptanceService billing;
    @Autowired LogisticsChannelRepository channels;
    @Autowired LogisticsQueryService queries; @MockitoBean AssetStorageService storage;

    private LogisticsImportService imports(){
        var objects=new HashMap<String,byte[]>();
        when(storage.putRawWithSha256(anyString(),any(InputStream.class),anyLong(),anyString())).thenAnswer(i->{
            var bytes=i.<InputStream>getArgument(1).readAllBytes();objects.put(i.getArgument(0),bytes);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        });
        when(storage.openRaw(anyString())).thenAnswer(i->new ByteArrayInputStream(objects.get(i.getArgument(0))));
        doAnswer(i->{objects.put(i.getArgument(0),i.<InputStream>getArgument(1).readAllBytes());return null;}).when(storage).putRaw(anyString(),any(InputStream.class),anyLong(),anyString());
        var service=new LogisticsImportService(jdbc,mapper,storage,parser,logistics,guard,manager,Runnable::run);
        ReflectionTestUtils.setField(service,"companyChannels",directory);return service;
    }
    private UUID provider(String name){
        var code="P-"+LogisticsDatasetService.hash(LogisticsDatasetService.normalize(name)).substring(0,16);
        return jdbc.sql("select id from logistics_provider where dataset_id=:d and code=:c").param("d",guard.activeId()).param("c",code).query(UUID.class).optional()
            .orElseGet(()->{
                var id=UUID.randomUUID();var p=mapper.createObjectNode().put("id",id.toString()).put("name",name).put("code",code).put("enabled",true);
                jdbc.sql("insert into logistics_provider(id,dataset_id,code,payload,created_at,updated_at) values(:id,:d,:c,cast(:p as jsonb),now(),now())")
                    .param("id",id).param("d",guard.activeId()).param("c",code).param("p",p.toString()).update();return id;
            });
    }
    private UUID channel(UUID p,String name,String code){var id=UUID.fromString(logistics.addChannel(mapper.createObjectNode().put("providerId",p.toString()).put("name",name).put("code",code).put("logisticsAttribute","化妆品")).path("id").asText());channels.flush();return id;}
    private UUID register(String name){
        var id=UUID.randomUUID();var entry=mapper.createObjectNode().put("id",id.toString()).put("providerName","燕文").put("channelName",name).put("logisticsAttribute","化妆品").put("enabled",true);
        if(name.equals("燕文化妆品快递（到门）")){entry.putArray("aliases").add("燕文化妆品快递");entry.putArray("productCodes").add("1224");}
        var all=directory.list();all.withArray("entries").add(entry);directory.save(all,"TEST");return id;
    }
    private JsonNode item(LogisticsImportService service,byte[] data,UUID channel){
        var batch=service.uploadSelected(guard.activeId(),List.of(new MockMultipartFile("files","燕文.xlsx","application/octet-stream",data)),"TEST",UUID.randomUUID().toString(),false,null,channel);
        var result=service.get(UUID.fromString(batch.path("id").asText()));assertEquals("completed",result.path("status").asText(),result.toString());
        assertEquals(1,result.path("payload").path("results").size());return result.path("payload").path("results").get(0);
    }
    private void publish(UUID channel,UUID version){new TransactionTemplate(manager).executeWithoutResult(s->{
        logistics.publishReviewed(channel,version,"隔离测试：核对原表价格", "TEST",true,true,true);
        billing.approveValidatedImport(version,"TEST","隔离测试原表已核对");
    });assertTrue(guard.quoteReady(version));}
    private String record(String table,UUID id){return jdbc.sql("select to_jsonb(t)::text from "+table+" t where id=:id").param("id",id).query(String.class).single();}
    private String finance(){return jdbc.sql("select to_jsonb(f)::text from finance_setting f where setting_key='channel-policies'").query(String.class).single();}
    private byte[] workbook(double rate)throws Exception{
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
            var sheet=book.createSheet("燕文化妆品快递");sheet.createRow(0).createCell(0).setCellValue("燕文化妆品快递");sheet.getRow(0).createCell(7).setCellValue("产品编号：1224");
            var labels=List.of("国家","CountryCode","参考时效(工作日)","重量段(KG)","最小计费重量(KG)","公斤运费(元/KG)","处理费(元/件)");var h=sheet.createRow(2);for(int c=0;c<labels.size();c++)h.createCell(c).setCellValue(labels.get(c));
            for(int i=0;i<2;i++){var r=sheet.createRow(3+i);r.createCell(0).setCellValue("以色列");r.createCell(1).setCellValue("IL");r.createCell(2).setCellValue("6-10");r.createCell(3).setCellValue(i==0?"0.001-5":"5.001-20");r.createCell(4).setCellValue(0.05);r.createCell(5).setCellValue(i==0?rate:63);r.createCell(6).setCellValue(i==0?51:50);}book.write(out);return out.toByteArray();
        }
    }
    @Test void boundManualChannelImportsPublishesAndUpdatesWithoutChangingIdentityFinanceOrHistory()throws Exception{
        var p=provider("燕文");var company=register("燕文化妆品快递（到门）");var c=channel(p,"燕文化妆品快递（到门）","YW-COSMETICS-1224");directory.bind(guard.activeId(),c,company.toString());
        var baseline=parser.parse(workbook(64),"燕文.xlsx",new CompanyChannelScope(directory.scopeFor(null,c))).path("channels").get(0);
        var manual=mapper.createObjectNode().put("actor","TEST").put("fileName","用户截图");manual.set("rows",baseline.path("rows"));
        var old=logistics.createManualDraft(c,manual);var oldId=UUID.fromString(old.path("id").asText());logistics.publishReviewed(c,oldId,"按截图录入","TEST",false,true,true);
        var oldRows=jdbc.sql("select payload->'rows' from logistics_version where id=:id").param("id",oldId).query(String.class).single();
        var beforeChannel=mapper.readTree(record("logistics_channel",c));String key=beforeChannel.path("rule_id").asText()+"::燕文::YW-COSMETICS-1224";
        var policies=mapper.createArrayNode();for(var attribute:List.of("化妆品","以色列到门")){var policy=policies.addObject().put("category",attribute).put("enabled",true);policy.putArray("countryRules").addObject().put("country","以色列").putArray("allowedChannels").add(key);}
        jdbc.sql("insert into finance_setting(setting_key,payload,version,updated_at) values('channel-policies',cast(:p as jsonb),0,now()) on conflict(setting_key) do update set payload=excluded.payload").param("p",policies.toString()).update();String beforeFinance=finance();
        var quote=UUID.randomUUID();jdbc.sql("insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at) values(:id,:no,'QA','quoted',cast(:p as jsonb),3,now(),now())").param("id",quote).param("no",quote.toString()).param("p",mapper.createObjectNode().put("freightCny",115).put("systemQuoteCny",200).put("customerQuote",220).put("reviewStatus","approved").put("processingStatus","completed").put("dealStatus","won").toString()).update();String beforeQuote=record("quotation_record",quote);
        var other=channel(p,"独立保留渠道","OTHER-"+UUID.randomUUID());String beforeOther=record("logistics_channel",other);
        var service=imports();String file=System.getenv("YANWEN_BINDING_SOURCE");byte[] bytes=file==null?workbook(64):Files.readAllBytes(Path.of(file));
        var first=item(service,bytes,c);assertEquals("draft",first.path("status").asText(),first.toString());assertEquals(c.toString(),first.path("channelId").asText());
        if(file!=null)assertEquals(14,first.path("priceRows").asInt());
        assertEquals(oldId.toString(),mapper.readTree(record("logistics_channel",c)).path("current_version_id").asText(),"Import alone must not publish");
        UUID v=UUID.fromString(first.path("versionId").asText());publish(c,v);
        assertEquals("unchanged",item(service,bytes,c).path("status").asText());
        var changed=item(service,workbook(65),c);assertEquals(c.toString(),changed.path("channelId").asText());assertEquals("draft",changed.path("status").asText(),changed.toString());publish(c,UUID.fromString(changed.path("versionId").asText()));
        var live=queries.publishedRules(null,"化妆品",List.of("以色列"),List.of("YW-COSMETICS-1224")).rules();assertEquals(1,live.size());assertEquals("燕文化妆品快递（到门）",live.getFirst().path("name").asText());assertEquals(65,live.getFirst().path("prices").get(0).path("pricePerKg").asDouble());
        var after=mapper.readTree(record("logistics_channel",c));for(var field:List.of("id","rule_id","code","provider_id","dataset_id"))assertEquals(beforeChannel.path(field),after.path(field));
        assertEquals(1,jdbc.sql("select count(*) from logistics_company_binding where dataset_id=:d and company_channel_id=:c").param("d",guard.activeId()).param("c",company).query(Integer.class).single());
        assertEquals(beforeFinance,finance());assertEquals(beforeQuote,record("quotation_record",quote));assertEquals(beforeOther,record("logistics_channel",other));
        assertEquals(oldRows,jdbc.sql("select payload->'rows' from logistics_version where id=:id").param("id",oldId).query(String.class).single());
        assertEquals(3,jdbc.sql("select count(*) from logistics_version where channel_id=:id").param("id",c).query(Integer.class).single());
    }
    @Test void japanFirstNextImportsPublishesRepeatsAndUpdatesWithoutTouchingHistory()throws Exception {
        var fixture=new YanwenJapanFirstNextTest();var bytes=fixture.fixture(.5,"日本");var service=imports();var p=provider("燕文");
        var quote=UUID.randomUUID();jdbc.sql("insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at) values(:id,:no,'QA','quoted','{\"freightCny\":115,\"reviewStatus\":\"approved\",\"dealStatus\":\"won\"}',3,now(),now())").param("id",quote).param("no",quote.toString()).update();String beforeQuote=record("quotation_record",quote);
        jdbc.sql("insert into finance_setting(setting_key,payload,version,updated_at) values('channel-policies','[]',0,now()) on conflict(setting_key) do nothing").update();String beforeFinance=finance();
        var unrelated=channel(p,"日本修复不影响的渠道","KEEP-"+UUID.randomUUID());String beforeOther=record("logistics_channel",unrelated);
        for(int i=0;i<4;i++) {
            var company=register(YanwenJapanFirstNextTest.NAMES[i]);var c=channel(p,YanwenJapanFirstNextTest.NAMES[i],"JP-"+YanwenJapanFirstNextTest.CODES[i]);directory.bind(guard.activeId(),c,company.toString());
            var parsed=parser.parse(bytes,"燕文.xlsx",new CompanyChannelScope(directory.scopeFor(null,c))).path("channels").get(0);var prior=mapper.createArrayNode();for(var r:parsed.path("rows"))if(!r.path("countryCode").asText().equals("JP"))prior.add(r);
            var manual=mapper.createObjectNode().put("actor","TEST").put("fileName","修复前价格");manual.set("rows",prior);var old=UUID.fromString(logistics.createManualDraft(c,manual).path("id").asText());logistics.publishReviewed(c,old,"修复前价格","TEST",false,true,true);
            String oldRows=jdbc.sql("select payload->'rows' from logistics_version where id=:id").param("id",old).query(String.class).single();
            var first=item(service,bytes,c);assertEquals("draft",first.path("status").asText(),first.toString());assertEquals(i<2?22:42,first.path("priceRows").asInt());publish(c,UUID.fromString(first.path("versionId").asText()));
            assertEquals("unchanged",item(service,bytes,c).path("status").asText());
            byte[] changed;try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes));var out=new ByteArrayOutputStream()){book.getSheet(YanwenJapanFirstNextTest.NAMES[i]).getRow(6).getCell(8).setCellValue(YanwenJapanFirstNextTest.FIRST[i][0]+1);book.write(out);changed=out.toByteArray();}
            var update=item(service,changed,c);assertEquals("draft",update.path("status").asText(),update.toString());publish(c,UUID.fromString(update.path("versionId").asText()));
            var live=queries.publishedRules(null,null,List.of("JP"),List.of("JP-"+YanwenJapanFirstNextTest.CODES[i])).rules();assertEquals(1,live.size());assertEquals(YanwenJapanFirstNextTest.FIRST[i][0]+1,new LogisticsBillingEngine(mapper).calculate(live.getFirst().path("prices"),mapper.createObjectNode().put("country","JP").put("weightKg",.5)).path("total").asDouble());
            assertEquals(oldRows,jdbc.sql("select payload->'rows' from logistics_version where id=:id").param("id",old).query(String.class).single());
            assertEquals(3,jdbc.sql("select count(*) from logistics_version where channel_id=:id").param("id",c).query(Integer.class).single());
        }
        assertEquals(beforeFinance,finance());assertEquals(beforeQuote,record("quotation_record",quote));assertEquals(beforeOther,record("logistics_channel",unrelated));
    }
    @Test void resolutionRejectsAmbiguousForeignProviderAndForeignCompanyBindings(){
        var service=imports();var p=provider("燕文");var tx=new TransactionTemplate(manager);
        tx.executeWithoutResult(s->{try{
            var company=register("隔离冲突测试"+UUID.randomUUID());String generated="C-"+LogisticsDatasetService.hash(company.toString()).substring(0,20);
            var custom=channel(p,"自定义编码","CUSTOM-"+UUID.randomUUID());directory.bind(guard.activeId(),custom,company.toString());
            assertEquals(Optional.of(custom),service.existingChannel(guard.activeId(),p,company.toString(),generated));
            assertThrows(AppException.class,()->service.existingChannel(guard.activeId(),UUID.randomUUID(),company.toString(),generated));
            var collision=channel(p,"自动编码冲突",generated);
            jdbc.sql("update logistics_channel set code=:code,payload=jsonb_set(payload,'{code}',to_jsonb(cast(:code as text))) where id=:id").param("code",generated).param("id",collision).update();
            assertThrows(AppException.class,()->service.existingChannel(guard.activeId(),p,company.toString(),generated));
            assertEquals(Optional.of(collision),service.existingChannel(guard.activeId(),p,"",generated));
            var foreign=register("其他公司渠道"+UUID.randomUUID());directory.bind(guard.activeId(),collision,foreign.toString());
            assertThrows(AppException.class,()->service.existingChannel(guard.activeId(),p,UUID.randomUUID().toString(),generated));
            assertEquals(company,jdbc.sql("select company_channel_id from logistics_company_binding where channel_id=:id").param("id",custom).query(UUID.class).single());
        }finally{s.setRollbackOnly();}});
    }
}
