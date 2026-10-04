package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.defer-datasource-initialization=false","spring.sql.init.mode=never","spring.session.store-type=none",
    "app.storage.initialize=false","app.logistics.resume-on-start=false","app.logistics.file-cleanup-enabled=false"})
@ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker=true)
class LogisticsParserCompatibilityPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired JdbcClient jdbc; @Autowired ObjectMapper mapper; @Autowired LogisticsService logistics;
    @Autowired LogisticsDatasetGuard guard; @Autowired LogisticsBatchPublishService publish;

    @Test void oldDraftPublishesWithOriginalProvenanceAndUnknownDraftRollsBack() throws Exception {
        var provider=logistics.addProvider(mapper.createObjectNode().put("name","兼容性测试").put("code","COMPAT"));
        var channel=logistics.addChannel(mapper.createObjectNode().put("providerId",provider.path("id").asText()).put("name","兼容性测试渠道").put("code","COMPAT-1"));
        var channelId=UUID.fromString(channel.path("id").asText());
        var input=mapper.createObjectNode().put("parserVersion",LogisticsParserCompatibility.SEPTEMBER_29).put("templateStatus","known").put("errors",0).put("fileName","old.xlsx").put("sourceHash","original-source");
        input.putArray("rows").addObject().put("countryCode","US").put("areaName","美国").put("zoneName","").put("currency","CNY")
            .put("weightFromKg",0).put("weightToKg",2).put("pricePerKg",10).put("registrationFee",2).put("pricingModel","per-kg").put("sourceSheet","原表").put("sourceRow",2);
        var draft=logistics.createDraft(channelId,input);var versionId=UUID.fromString(draft.path("id").asText());
        var batch=batch(channelId,versionId);var request=request(channelId,versionId);
        var view=batch.deepCopy();publish.applyParserEligibility(UUID.fromString(batch.path("id").asText()),view);
        assertTrue(view.path("payload").path("results").get(0).path("pricingReady").asBoolean());
        var result=publish.publishReady(UUID.fromString(batch.path("id").asText()),request,"COMPAT-QA");
        assertEquals(1,result.path("publishedCount").asInt(),result.toString());assertTrue(guard.quoteReady(versionId));
        var stored=mapper.readTree(jdbc.sql("select payload::text from logistics_version where id=:id").param("id",versionId).query(String.class).single());
        assertEquals(LogisticsParserCompatibility.SEPTEMBER_29,stored.path("parserVersion").asText());assertEquals(draft.path("sourceHash"),stored.path("sourceHash"));
        assertEquals("original-source",stored.path("contentHash").asText());
        var audit=mapper.readTree(jdbc.sql("select payload::text from logistics_billing_acceptance where version_id=:id").param("id",versionId).query(String.class).single());
        assertEquals(LogisticsParserCompatibility.SEPTEMBER_29,audit.path("parserVersion").asText());assertEquals(LogisticsSourceParser.VERSION,audit.path("validationParserVersion").asText());
        assertEquals(1,publish.publishReady(UUID.fromString(batch.path("id").asText()),request,"COMPAT-QA").path("publishedCount").asInt());
        assertEquals(1,jdbc.sql("select count(*) from logistics_billing_acceptance where version_id=:id").param("id",versionId).query(Integer.class).single());

        input.put("parserVersion","unverified-parser").put("sourceHash","unknown-source");
        var unknown=logistics.createDraft(channelId,input);var unknownId=UUID.fromString(unknown.path("id").asText());
        var unknownBatch=batch(channelId,unknownId);var unknownView=unknownBatch.deepCopy();
        publish.applyParserEligibility(UUID.fromString(unknownBatch.path("id").asText()),unknownView);
        assertFalse(unknownView.path("payload").path("results").get(0).path("pricingReady").asBoolean());
        assertEquals("draft",unknownBatch.path("payload").path("results").get(0).path("status").asText(),"Read projection must not overwrite import evidence");
        result=publish.publishReady(UUID.fromString(unknownBatch.path("id").asText()),request(channelId,unknownId),"COMPAT-QA");
        assertEquals(1,result.path("skippedCount").asInt());assertTrue(result.path("skipped").get(0).path("reason").asText().contains("兼容性"));
        assertEquals(versionId,jdbc.sql("select current_version_id from logistics_channel where id=:id").param("id",channelId).query(UUID.class).single());
        assertEquals("draft",jdbc.sql("select status from logistics_version where id=:id").param("id",unknownId).query(String.class).single());
        assertEquals(0,jdbc.sql("select count(*) from logistics_billing_acceptance where version_id=:id").param("id",unknownId).query(Integer.class).single());
        // Trusting the producer never bypasses real validation errors.
        jdbc.sql("update logistics_version set payload=payload || cast(:patch as jsonb) where id=:id")
            .param("patch",mapper.createObjectNode().put("parserVersion",LogisticsParserCompatibility.SEPTEMBER_29).put("errors",1).toString()).param("id",unknownId).update();
        result=publish.publishReady(UUID.fromString(unknownBatch.path("id").asText()),request(channelId,unknownId),"COMPAT-QA");
        assertEquals(1,result.path("skippedCount").asInt());assertTrue(result.path("skipped").get(0).path("reason").asText().contains("阻断错误"));
    }

    private ObjectNode batch(UUID channel,UUID version){
        var id=UUID.randomUUID();var payload=mapper.createObjectNode();
        payload.putArray("results").addObject().put("versionId",version.toString()).put("channelId",channel.toString()).put("providerName","兼容性测试").put("channelName","兼容性测试渠道").put("status","draft").put("pricingReady",true);
        jdbc.sql("insert into logistics_import_batch(id,dataset_id,requested_by,request_key,status,phase,payload) values(:id,:dataset,'QA',:key,'completed','review',cast(:payload as jsonb))")
            .param("id",id).param("dataset",guard.activeId()).param("key",id.toString()).param("payload",payload.toString()).update();
        return mapper.createObjectNode().put("id",id.toString()).set("payload",payload);
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="logistics.compat.fixture",matches=".+")
    void retainedProductionDraftsPublishInIsolatedDatabase() throws Exception {
        var fixtures=mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("logistics.compat.fixture"))));
        assertEquals(12,fixtures.size());int rows=0;
        var providers=new java.util.HashMap<String,String>();
        for(var fixture:fixtures){
            var p=fixture.path("provider");var originalChannel=fixture.path("channel");
            var providerId=providers.computeIfAbsent(p.path("code").asText(),key->logistics.addProvider(mapper.createObjectNode().put("name",p.path("name").asText()).put("code",key)).path("id").asText());
            var c=(ObjectNode)originalChannel.deepCopy();c.put("providerId",providerId);
            var channel=logistics.addChannel(c);var channelId=UUID.fromString(channel.path("id").asText());
            if(fixture.path("baseline").isObject()){
                var baseline=(ObjectNode)fixture.path("baseline").deepCopy();baseline.put("sourceHash","compat-baseline:"+UUID.randomUUID());
                var base=logistics.createDraft(channelId,baseline);var baseId=UUID.fromString(base.path("id").asText());
                jdbc.sql("update logistics_version set status='published',payload=jsonb_set(payload,'{status}','\"published\"') where id=:id").param("id",baseId).update();
                jdbc.sql("update logistics_channel set current_version_id=:version where id=:id").param("version",baseId).param("id",channelId).update();
            }
            var input=(ObjectNode)fixture.path("draft").deepCopy();
            var draft=logistics.createDraft(channelId,input);var id=UUID.fromString(draft.path("id").asText());
            var b=batch(channelId,id);var batchId=UUID.fromString(b.path("id").asText());
            // The synthetic batch must carry actual provider names for coverage checks.
            var item=(ObjectNode)b.path("payload").path("results").get(0);
            item.put("providerName",p.path("name").asText()).put("channelName",c.path("name").asText());
            jdbc.sql("update logistics_import_batch set payload=cast(:payload as jsonb) where id=:id").param("payload",b.path("payload").toString()).param("id",batchId).update();
            var approval=request(channelId,id);var coverage=publish.coverage(batchId);
            approval.put("coverageToken",coverage.path("token").asText()).put("partialUpdateConfirmed",true);
            var outcome=publish.publishReady(batchId,approval,"COMPAT-CORPUS-QA");
            assertEquals(1,outcome.path("publishedCount").asInt(),c.path("name")+outcome.toString());
            assertTrue(guard.quoteReady(id),c.path("name").asText());
            var after=mapper.readTree(jdbc.sql("select payload::text from logistics_version where id=:id").param("id",id).query(String.class).single());
            assertEquals(input.path("parserVersion"),after.path("parserVersion"));
            assertEquals(draft.path("rows"),after.path("rows"),"Publication must preserve validated prices");
            rows+=after.path("rows").size();
        }
        assertEquals(676,rows);
    }
    private ObjectNode request(UUID channel,UUID version){
        var input=mapper.createObjectNode().put("note","隔离测试：核对兼容性和计费");
        input.putArray("selections").addObject().put("versionId",version.toString()).put("channelId",channel.toString()).put("reviewConfirmed",true).put("removalConfirmed",true);
        return input;
    }
}
