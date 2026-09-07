package com.milano.quotation.logistics;

import com.milano.quotation.storage.AssetStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="company.corpus",matches=".+")
@SpringBootTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate","spring.jpa.defer-datasource-initialization=false","spring.sql.init.mode=never","spring.session.store-type=none","app.storage.initialize=false","app.logistics.resume-on-start=false","app.logistics.file-cleanup-enabled=false"})
@ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS) @Testcontainers
class CompanyBaselineWorkflowTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);}
    @Autowired JdbcClient jdbc;@Autowired ObjectMapper mapper;@Autowired CompanyPriceRebuildService rebuild;@Autowired CompanyChannelService directory;
    @Autowired LogisticsSourceParser parser;@Autowired LogisticsService logistics;@Autowired LogisticsDatasetGuard guard;@Autowired PlatformTransactionManager manager;
    @Autowired LogisticsBatchPublishService publish;@Autowired LogisticsQueryService queries;
    @MockitoBean AssetStorageService storage;
    @Test void importsAllElevenRealFilesIntoEightyEightStableCompanyChannels()throws Exception{
        var objects=new HashMap<String,byte[]>();
        doAnswer(call->{objects.put(call.getArgument(0),((InputStream)call.getArgument(1)).readAllBytes());return null;}).when(storage).putRaw(anyString(),any(),anyLong(),anyString());
        when(storage.putRawWithSha256(anyString(),any(),anyLong(),anyString())).thenAnswer(call->{var bytes=((InputStream)call.getArgument(1)).readAllBytes();objects.put(call.getArgument(0),bytes);return AssetStorageService.sha256(bytes);});
        when(storage.openRaw(anyString())).thenAnswer(call->new ByteArrayInputStream(Objects.requireNonNull(objects.get(call.getArgument(0)))));
        when(storage.removeRaw(anyString())).thenAnswer(call->{objects.remove(call.getArgument(0));return true;});
        var job=rebuild.begin(rebuild.preview().put("note","真实8.27基准重建验证"),"CORPUS-QA");var jobId=UUID.fromString(job.path("id").asText());
        var dataset=UUID.fromString(job.path("payload").path("targetDatasetId").asText());
        rebuild.backup(jobId);rebuild.purge(jobId,mapper.createObjectNode().put("deleteConfirmed",true));rebuild.cleanupObjects(jobId);
        var files=new ArrayList<MultipartFile>();try(var paths=Files.list(Path.of(System.getProperty("company.corpus")))){for(var file:paths.filter(p->p.getFileName().toString().matches("(?i)^(?!~\\$).*\\.xlsx?$")).sorted().toList())files.add(new MockMultipartFile("files",file.getFileName().toString(),"application/octet-stream",Files.readAllBytes(file)));}
        var imports=new LogisticsImportService(jdbc,mapper,storage,parser,logistics,guard,manager,command->{});
        ReflectionTestUtils.setField(imports,"companyChannels",directory);
        var started=System.nanoTime();var accepted=imports.upload(dataset,files,"CORPUS-QA","company-real-baseline",false);var id=UUID.fromString(accepted.path("id").asText());imports.process(id);
        var batch=imports.get(id);Files.writeString(Path.of("target/company-baseline-import.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(batch));
        assertEquals("completed",batch.path("status").asText(),batch.path("payload").path("error").asText());
        assertEquals(88,batch.path("payload").path("results").size());
        assertEquals(88,jdbc.sql("select count(*) from logistics_company_binding where dataset_id=:id").param("id",dataset).query(Integer.class).single());
        for(var result:batch.path("payload").path("results"))assertFalse(result.path("versionId").asText().isBlank(),result.toString());
        assertEquals(0,jdbc.sql("select count(*) from logistics_version where status='published'").query(Integer.class).single());
        assertTrue(directory.state(false).path("paused").asBoolean());
        var approval=mapper.createObjectNode().put("note","本地真实基准：源表逐渠道对账，全部解析价格哈希一致；缺失时效保持阻断");var selections=approval.putArray("selections");
        for(var item:batch.path("payload").path("results"))if(item.path("pricingReady").asBoolean()&&item.path("etaReady").asBoolean())selections.addObject().put("channelId",item.path("channelId").asText()).put("versionId",item.path("versionId").asText()).put("reviewConfirmed",true).put("removalConfirmed",true);
        var publication=publish.publishReady(id,approval,"CORPUS-QA");assertEquals(0,publication.path("failed").size(),publication.toString());assertEquals(selections.size(),publication.path("published").size(),publication.toString());
        assertEquals(0,queries.manifest().publishedChannels(),"审核期间仍不开放新报价");
        var finished=rebuild.finish(jobId,mapper.createObjectNode().put("note","全部88渠道有明确状态").put("reviewConfirmed",true).put("unavailableConfirmed",true),"CORPUS-QA");
        assertEquals("completed",finished.path("phase").asText());assertEquals(83,queries.manifest().publishedChannels());assertEquals(5,finished.path("payload").path("blockedChannels").size());
        var beforeRevision=queries.manifestRevision().revision();var edited=directory.snapshot();var first=edited.path("entries").valueStream().filter(e->jdbc.sql("select exists(select 1 from logistics_company_binding b join logistics_channel c on c.id=b.channel_id where b.company_channel_id=:id and c.current_version_id is not null)").param("id",UUID.fromString(e.path("id").asText())).query(Boolean.class).single()).findFirst().orElseThrow();
        ((tools.jackson.databind.node.ObjectNode)first).put("enabled",false);directory.save(edited,"CORPUS-QA");
        assertNotEquals(beforeRevision,queries.manifestRevision().revision());assertEquals(82,queries.manifest().publishedChannels());
        System.out.println("Company baseline end-to-end import: elapsedMs="+(System.nanoTime()-started)/1_000_000+", parsingMs="+batch.path("payload").path("parsingMs")+", stagingMs="+batch.path("payload").path("stagingMs"));
    }
}
