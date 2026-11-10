package com.milano.quotation.logistics;

import com.milano.quotation.storage.AssetStorageService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.defer-datasource-initialization=false","spring.sql.init.mode=never","spring.session.store-type=none",
    "app.storage.initialize=false","app.logistics.resume-on-start=false","app.logistics.file-cleanup-enabled=false"})
@ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class KuailvWorkflowPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired JdbcClient jdbc; @Autowired ObjectMapper mapper; @Autowired LogisticsSourceParser parser;
    @Autowired LogisticsService logistics; @Autowired LogisticsDatasetGuard guard; @Autowired PlatformTransactionManager manager;
    @Autowired LogisticsBatchPublishService publish; @Autowired LogisticsQueryService query;
    @MockitoBean AssetStorageService storage;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void publicationProjectionAndAcceptanceRequireTheNewEngineWithoutChangingHistory(boolean specialLine)throws Exception {
        var priorVersions=jdbc.sql("select id::text as id,md5(to_jsonb(v)::text) as hash from logistics_version v").query().listOfRows();
        var saved=UUID.randomUUID();
        jdbc.sql("insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at) values(:id,:no,'QA','quoted','{\"internationalFreight\":23.09,\"systemQuote\":200,\"customerQuote\":220,\"reviewStatus\":\"approved\",\"processingStatus\":\"completed\",\"dealStatus\":\"won\"}'::jsonb,3,now(),now())")
            .param("id",saved).param("no",saved.toString()).update();
        var before=jdbc.sql("select to_jsonb(q)::text from quotation_record q where id=:id").param("id",saved).query(String.class).single();
        var objects=new HashMap<String,byte[]>();
        when(storage.putRawWithSha256(anyString(),any(InputStream.class),anyLong(),anyString())).thenAnswer(i->{
            var bytes=i.<InputStream>getArgument(1).readAllBytes();objects.put(i.getArgument(0),bytes);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        });
        when(storage.openRaw(anyString())).thenAnswer(i->new ByteArrayInputStream(objects.get(i.getArgument(0))));
        var imports=new LogisticsImportService(jdbc,mapper,storage,parser,logistics,guard,manager,Runnable::run);
        byte[] source=specialLine?new KuailvSpecialLineTest().onlySpecialFixture():new KuailvSourceTest().fixture(false);
        var accepted=imports.upload(guard.activeId(),List.of(new MockMultipartFile("files","快驴.xlsx","application/octet-stream",source)),"QA",UUID.randomUUID().toString(),true);
        var batch=imports.get(UUID.fromString(accepted.path("id").asText()));assertEquals("completed",batch.path("status").asText(),batch.toString());
        var result=batch.path("payload").path("results").get(0);assertEquals("draft",result.path("status").asText(),result.toString());
        var version=UUID.fromString(result.path("versionId").asText());
        var input=mapper.createObjectNode().put("note","原表及用户确认取整规则，独立核对");
        input.putArray("selections").addObject().put("versionId",version.toString()).put("channelId",result.path("channelId").asText()).put("reviewConfirmed",true).put("removalConfirmed",true);
        assertEquals(1,publish.publishReady(UUID.fromString(batch.path("id").asText()),input,"QA").path("publishedCount").asInt());
        assertTrue(guard.quoteReady(version));
        var name=specialLine?KuailvSpecialLineTest.NAME:KuailvSourceTest.NAME;
        var projected=query.publishedRules(null,null,List.of("US"),List.of()).rules().stream().filter(r->r.path("relations").valueStream().anyMatch(rel->rel.path("channel").asText().equals(name))).findFirst().orElseThrow().path("prices");
        var row=projected.get(0);
        assertEquals("per-kg-1g-ceil-cny",row.path("pricingModel").asText());
        var exported=new LogisticsExportService(jdbc,mapper).prices(guard.activeId(),version,"","","");
        var roundtrip=parser.parse(exported,"快驴标准模板.xlsx").path("channels").get(0);
        assertEquals(6,roundtrip.path("rows").size());assertTrue(roundtrip.path("quoteReady").asBoolean(),roundtrip.toPrettyString());
        for(var price:roundtrip.path("rows"))assertEquals("per-kg-1g-ceil-cny",price.path("pricingModel").asText());
        assertEquals(24,new LogisticsBillingEngine(mapper).calculate(mapper.createArrayNode().add(row),mapper.createObjectNode().put("country","US").put("weightKg",.0005)).path("total").asInt());
        assertEquals(specialLine?64:61,new LogisticsBillingEngine(mapper).calculate(projected,mapper.createObjectNode().put("country","US").put("weightKg",.453)).path("total").asInt());
        jdbc.sql("update logistics_billing_acceptance set engine_version='logistics-billing-v9' where version_id=:id").param("id",version).update();
        assertFalse(guard.quoteReady(version),"Older approvals cannot authorize monetary rounding");
        jdbc.sql("update logistics_billing_acceptance set engine_version='logistics-billing-v10' where version_id=:id").param("id",version).update();assertTrue(guard.quoteReady(version));
        assertEquals(before,jdbc.sql("select to_jsonb(q)::text from quotation_record q where id=:id").param("id",saved).query(String.class).single());
        var repeat=imports.upload(guard.activeId(),List.of(new MockMultipartFile("files","快驴.xlsx","application/octet-stream",source)),"QA",UUID.randomUUID().toString(),true);
        assertEquals("unchanged",imports.get(UUID.fromString(repeat.path("id").asText())).path("payload").path("results").get(0).path("status").asText());
        for(var prior:priorVersions)assertEquals(prior.get("hash"),jdbc.sql("select md5(to_jsonb(v)::text) from logistics_version v where id=:id").param("id",UUID.fromString(prior.get("id").toString())).query(String.class).single(),"A different channel's existing price version changed");
    }
}
