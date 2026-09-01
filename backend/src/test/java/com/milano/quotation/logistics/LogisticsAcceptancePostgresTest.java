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
class LogisticsAcceptancePostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    static final ObjectMapper mapper=new ObjectMapper();static JdbcClient jdbc;static TransactionTemplate tx;static LogisticsDatasetService datasets;static LogisticsDatasetGuard guard;static LogisticsBillingAcceptanceService billing;
    @BeforeAll static void setup()throws Exception{
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration").load().migrate();
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());jdbc=JdbcClient.create(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));guard=new LogisticsDatasetGuard(jdbc);
        var storage=mock(AssetStorageService.class);var saved=new HashMap<String,byte[]>();doAnswer(i->{saved.put(i.getArgument(0),((InputStream)i.getArgument(1)).readAllBytes());return null;}).when(storage).putRaw(anyString(),any(),anyLong(),anyString());when(storage.openRaw(anyString())).thenAnswer(i->new ByteArrayInputStream(saved.get(i.getArgument(0))));
        datasets=new LogisticsDatasetService(jdbc,mapper,guard,storage);billing=new LogisticsBillingAcceptanceService(jdbc,mapper,guard,new LogisticsBillingEngine(mapper));
    }
    @Test void clientFlagsDoNotGrantReadinessAndRequiredSelectionIsVersioned(){tx.executeWithoutResult(s->{try{
        UUID d=create(),v=seed(d);assertFalse(billing.status(v).path("quoteReady").asBoolean());
        var c=channel(v);var selected=mapper.createObjectNode().put("revision",0).put("confirmed",true).put("note","用户选择");selected.putArray("channelIds").add(c.toString());
        var result=datasets.saveRequiredChannels(d,selected,"QA");assertEquals(1,result.path("revision").asInt());assertThrows(AppException.class,()->datasets.saveRequiredChannels(d,selected,"QA"));
        assertFalse(datasets.preview(d,mapper.createArrayNode()).path("requiredReady").asBoolean());billing.approve(v,approval(v),"QA");assertTrue(datasets.preview(d,mapper.createArrayNode()).path("requiredReady").asBoolean());
        assertEquals(1,jdbc.sql("select count(*) from logistics_required_revision where dataset_id=:id").param("id",d).query(Integer.class).single());
    }finally{s.setRollbackOnly();}});}
    @Test void financePreviewReturnsOnlySelectedPreparingChannelsAndReadiness(){tx.executeWithoutResult(s->{try{
        UUID d=create(),v=seed(d),c=channel(v);var selected=mapper.createObjectNode().put("revision",0).put("confirmed",true).put("note","财务预览必用渠道");selected.putArray("channelIds").add(c.toString());
        datasets.saveRequiredChannels(d,selected,"QA");var summaries=datasets.preparingRequiredPreviews();var summary=summaries.stream().filter(item->item.path("id").asText().equals(d.toString())).findFirst().orElseThrow();
        assertTrue(summary.path("confirmed").asBoolean());assertEquals(1,summary.path("requiredCount").asInt());var preview=datasets.requiredChannelPreview(d);
        assertEquals(1,preview.path("channels").size());assertEquals(0,preview.path("readyCount").asInt());assertEquals(c.toString(),preview.path("channels").get(0).path("id").asText());
        billing.approve(v,approval(v),"QA");assertEquals(1,datasets.requiredChannelPreview(d).path("readyCount").asInt());
    }finally{s.setRollbackOnly();}});}
    @Test void acceptanceRequiresEveryRowSamplesAccurateCostsAndMatchingFingerprint(){tx.executeWithoutResult(s->{try{
        UUID d=create(),v=seed(d);var invalid=approval(v);((ObjectNode)invalid.path("samples").get(0)).put("expectedTotal",0);assertThrows(AppException.class,()->billing.approve(v,invalid,"QA"));
        var stale=approval(v).put("fingerprint","stale");assertThrows(AppException.class,()->billing.approve(v,stale,"QA"));
        var partial=approval(v);((ArrayNode)partial.path("samples")).remove(1);assertThrows(AppException.class,()->billing.approve(v,partial,"QA"));
        var repeated=approval(v);((ObjectNode)repeated.path("samples").get(1)).put("expectedTotal",20).set("input",LogisticsBillingEngineTest.input(.2));assertThrows(AppException.class,()->billing.approve(v,repeated,"QA"));
        billing.approve(v,approval(v),"QA");assertTrue(guard.quoteReady(v));
        jdbc.sql("update logistics_version set payload=jsonb_set(payload,'{rows,0,pricePerKg}','99') where id=:id").param("id",v).update();assertFalse(guard.quoteReady(v));
    }finally{s.setRollbackOnly();}});}
    @Test void unsupportedRulesCannotBeApprovedAndNewVersionsDoNotInheritAcceptance(){tx.executeWithoutResult(s->{try{
        UUID d=create(),v=seed(d);jdbc.sql("update logistics_version set payload=jsonb_set(payload,'{rows}',(payload->'rows') || jsonb_build_array((payload->'rows'->0) || jsonb_build_object('pricePerKg',60,'originRegion','华东'))) where id=:id").param("id",v).update();assertThrows(AppException.class,()->billing.approve(v,approval(v),"QA"));
        jdbc.sql("update logistics_version set payload=jsonb_set(payload,'{rows}',jsonb_build_array(payload->'rows'->0)) where id=:id").param("id",v).update();billing.approve(v,approval(v),"QA");
        UUID next=UUID.randomUUID();jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at) select :next,channel_id,2,'published','copy',payload,now() from logistics_version where id=:id").param("next",next).param("id",v).update();assertFalse(guard.quoteReady(next));
        jdbc.sql("update logistics_billing_acceptance set engine_version='outdated-engine' where version_id=:id").param("id",v).update();assertFalse(guard.quoteReady(v));
    }finally{s.setRollbackOnly();}});}
    @Test void identicalPhysicalRowsShareOneAcceptanceTier(){tx.executeWithoutResult(s->{try{
        UUID d=create(),v=seed(d);
        jdbc.sql("update logistics_version set payload=jsonb_set(payload,'{rows}',(payload->'rows') || jsonb_build_array(payload->'rows'->0)) where id=:id").param("id",v).update();
        assertDoesNotThrow(()->billing.approve(v,approval(v),"QA"));assertTrue(guard.quoteReady(v));
    }finally{s.setRollbackOnly();}});}
    @Test void requiredMissingAndStalePreviewPreventSwitchEvenWithReadyChannel(){tx.executeWithoutResult(s->{try{
        var old=guard.activeId();UUID d=create(),v=seed(d);billing.approve(v,approval(v),"QA");datasets.backup(d,"QA");
        var preview=datasets.preview(d,mapper.createArrayNode()).put("note","QA").put("reviewConfirmed",true).put("unavailableConfirmed",true);assertThrows(AppException.class,()->datasets.activate(d,preview,"QA"));
        var list=mapper.createObjectNode().put("revision",0).put("confirmed",true).put("note","必用");list.putArray("channelIds").add(channel(v).toString());datasets.saveRequiredChannels(d,list,"QA");
        assertThrows(AppException.class,()->datasets.activate(d,preview,"QA"));assertEquals(old,guard.activeId());
        var latest=datasets.preview(d,mapper.createArrayNode()).put("note","QA").put("reviewConfirmed",true).put("unavailableConfirmed",true);datasets.activate(d,latest,"QA");assertEquals(d,guard.activeId());
    }finally{s.setRollbackOnly();}});}
    static UUID create(){return UUID.fromString(datasets.create("仅隔离测试","QA").path("id").asText());}
    static UUID channel(UUID v){return jdbc.sql("select channel_id from logistics_version where id=:id").param("id",v).query(UUID.class).single();}
    static UUID seed(UUID d){UUID p=UUID.randomUUID(),c=UUID.randomUUID(),v=UUID.randomUUID();
        jdbc.sql("insert into logistics_provider(id,dataset_id,code,payload,created_at,updated_at) values(:id,:d,:code,'{\"name\":\"测试商\",\"enabled\":true}',now(),now())").param("id",p).param("d",d).param("code",p.toString()).update();
        jdbc.sql("insert into logistics_channel(id,dataset_id,provider_id,rule_id,code,payload,created_at,updated_at) values(:id,:d,:p,:rule,:code,'{\"name\":\"测试渠道\",\"enabled\":true}',now(),now())").param("id",c).param("d",d).param("p",p).param("rule",guard.nextRuleId()).param("code",c.toString()).update();
        var payload=mapper.createObjectNode().put("id",v.toString()).put("quoteReady",true);payload.putArray("rows").add(LogisticsBillingEngineTest.row(0,1,50).put("quoteReady",true));
        jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at) values(:id,:c,1,'published','fixture',cast(:payload as jsonb),now())").param("id",v).param("c",c).param("payload",payload.toString()).update();jdbc.sql("update logistics_channel set current_version_id=:v where id=:c").param("v",v).param("c",c).update();return v;
    }
    static ObjectNode approval(UUID v){var a=billing.status(v).put("reviewConfirmed",true).put("note","人工核对50/kg+10/票").put("sourceReference","测试数据独立手算");var samples=a.putArray("samples");for(double weight:List.of(.2,.8))samples.addObject().put("sourceReference","50*重量+10").put("expectedTotal",weight*50+10).set("input",LogisticsBillingEngineTest.input(weight));samples.addObject().put("sourceReference","超重").put("expectRejected",true).set("input",LogisticsBillingEngineTest.input(2));return a;}
}
