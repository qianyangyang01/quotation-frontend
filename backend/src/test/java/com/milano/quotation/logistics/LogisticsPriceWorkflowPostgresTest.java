package com.milano.quotation.logistics;

import com.milano.quotation.storage.AssetStorageService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.defer-datasource-initialization=false","spring.sql.init.mode=never","spring.session.store-type=none",
    "app.storage.initialize=false","app.logistics.resume-on-start=false","app.logistics.file-cleanup-enabled=false"})
@ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker=true)
class LogisticsPriceWorkflowPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired JdbcClient jdbc; @Autowired ObjectMapper mapper; @Autowired LogisticsSourceParser parser;
    @Autowired LogisticsService logistics; @Autowired LogisticsDatasetGuard guard; @Autowired PlatformTransactionManager manager;
    @Autowired LogisticsDraftReviewService review; @Autowired LogisticsBatchPublishService publish;
    @Autowired LogisticsQueryService query; @MockitoBean AssetStorageService storage;

    @Test void importFillEtaPublishAndUpdateKeepTheFinanceChannelAndUseNewBasePrice()throws Exception {
        var objects=new HashMap<String,byte[]>();
        when(storage.putRawWithSha256(anyString(),any(InputStream.class),anyLong(),anyString())).thenAnswer(i->{
            var bytes=i.<InputStream>getArgument(1).readAllBytes();objects.put(i.getArgument(0),bytes);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        });
        when(storage.openRaw(anyString())).thenAnswer(i->new ByteArrayInputStream(objects.get(i.getArgument(0))));
        var imports=new LogisticsImportService(jdbc,mapper,storage,parser,logistics,guard,manager,Runnable::run);
        var first=upload(imports,10,"运输说明：另行确认偏远区域。");
        var item=first.path("payload").path("results").get(0);
        assertEquals("draft",item.path("status").asText(),first.toString());assertFalse(item.path("etaReady").asBoolean());
        var noEtaVersion=UUID.fromString(item.path("versionId").asText());
        publish(first);assertTrue(guard.quoteReady(noEtaVersion));assertEquals(12,total(noEtaVersion,1));
        assertEquals(1,query.publishedCatalog(null).rules().size());
        var quoteRows=query.publishedRules(null,null,List.of("US"),List.of()).rules().getFirst().path("prices");
        assertEquals("missing",quoteRows.get(0).path("etaStatus").asText());assertEquals(0,quoteRows.get(0).path("etaMinDays").asInt());
        first=upload(imports,11,"运输说明更新。");item=first.path("payload").path("results").get(0);
        var version=UUID.fromString(item.path("versionId").asText());var channel=item.path("channelId").asText();
        var draft=review.load(version,false);var patch=mapper.createObjectNode().put("fingerprint",draft.path("fingerprint").asText());
        patch.putArray("changes").addObject().put("rowKey",draft.path("rows").get(0).path("rowKey").asText()).putObject("fields").put("pricePerKg",12).put("registrationFee",3).put("weightFromKg",0.1);
        patch.putArray("etaChanges").addObject().put("routeKey",draft.path("missingEtaRoutes").get(0).path("routeKey").asText()).put("etaMinDays",7).put("etaMaxDays",15);
        var fixed=review.patch(version,patch,"TEST-ETA");assertTrue(fixed.path("pricingReady").asBoolean(),fixed.toString());
        assertEquals(0.1,fixed.path("rows").get(0).path("weightFromKg").asDouble());
        assertEquals(12,fixed.path("rows").get(0).path("pricePerKg").asDouble());assertEquals(3,fixed.path("rows").get(0).path("registrationFee").asDouble());
        var refreshed=imports.get(UUID.fromString(first.path("id").asText()));
        assertTrue(refreshed.path("payload").path("results").get(0).path("pendingReasons").isEmpty(),refreshed.toString());
        publish(first);assertTrue(guard.quoteReady(version));assertEquals(15,total(version,1));
        var catalog=query.publishedCatalog(null);assertEquals(1,catalog.rules().size());
        var binding=catalog.rules().getFirst().path("logisticsChannelId").asText();assertEquals(channel,binding);

        var second=upload(imports,20,"运输说明已更新。");var updated=second.path("payload").path("results").get(0);
        assertEquals(channel,updated.path("channelId").asText());assertTrue(updated.path("etaReady").asBoolean(),second.toString());
        var secondId=UUID.fromString(updated.path("versionId").asText());assertNotEquals(version,secondId);
        assertEquals("manual-review-inherited",review.load(secondId,false).path("rows").get(0).path("etaSource").asText());
        publish(second);assertEquals(22,total(secondId,1));
        assertEquals(binding,query.publishedCatalog(null).rules().getFirst().path("logisticsChannelId").asText());
        assertEquals(secondId,jdbc.sql("select current_version_id from logistics_channel where id=:id").param("id",UUID.fromString(channel)).query(UUID.class).single());
        assertEquals("superseded",jdbc.sql("select status from logistics_version where id=:id").param("id",version).query(String.class).single());
        assertTrue(publish.progress(UUID.fromString(first.path("id").asText())).path("publishedVersionIds").isEmpty(),"A superseded version must not appear as currently published");

        var repeated=upload(imports,20,"运输说明再次调整，不影响价格。");
        assertEquals("unchanged",repeated.path("payload").path("results").get(0).path("status").asText(),repeated.toString());
        assertEquals(3,jdbc.sql("select count(*) from logistics_version where channel_id=:id").param("id",UUID.fromString(channel)).query(Integer.class).single());
    }
    private ObjectNode upload(LogisticsImportService imports,double rate,String notes)throws Exception {
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("普通渠道");var header=sheet.createRow(0);var labels=List.of("国家","重量段","运费/KG","挂号费/票");
            for(int i=0;i<labels.size();i++)header.createCell(i).setCellValue(labels.get(i));
            for(int i=1;i<=2;i++){var row=sheet.createRow(i);row.createCell(0).setCellValue("美国");row.createCell(1).setCellValue(i==1?"0-1":"1.001-2");row.createCell(2).setCellValue(rate);row.createCell(3).setCellValue(2);}
            sheet.createRow(4).createCell(0).setCellValue(notes);book.write(out);
            var accepted=imports.upload(guard.activeId(),List.of(new MockMultipartFile("files","花海.xlsx","application/octet-stream",out.toByteArray())),"TEST-UPLOAD",UUID.randomUUID().toString(),true);
            var result=imports.get(UUID.fromString(accepted.path("id").asText()));assertEquals("completed",result.path("status").asText(),result.toString());return result;
        }
    }
    private void publish(JsonNode batch){
        var batchId=UUID.fromString(batch.path("id").asText());
        assertTrue(publish.progress(batchId).path("publishedVersionIds").isEmpty(),"Drafts must not advance publication progress");
        var input=mapper.createObjectNode().put("note","仅隔离测试，核对价格和重量区间");input.putArray("selections").addObject().put("versionId",batch.path("payload").path("results").get(0).path("versionId").asText()).put("reviewConfirmed",true).put("removalConfirmed",true);
        var result=publish.publishReady(UUID.fromString(batch.path("id").asText()),input,"TEST-PUBLISH");assertEquals(1,result.path("publishedCount").asInt(),result.toString());
        var progress=publish.progress(batchId);assertEquals(1,progress.path("publishedVersionIds").size());
        assertEquals(batch.path("payload").path("results").get(0).path("versionId").asText(),progress.path("publishedVersionIds").get(0).asText());
    }
    private double total(UUID id,double weight){
        var payload=review.load(id,false);return new LogisticsBillingEngine(mapper).calculate(payload.path("rows"),mapper.createObjectNode().put("country","US").put("weightKg",weight)).path("total").asDouble();
    }
}
