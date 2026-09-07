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
import tools.jackson.databind.node.ObjectNode;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
class CompanyPriceRebuildPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    static JdbcClient jdbc;static TransactionTemplate tx;
    final ObjectMapper mapper=new ObjectMapper();
    @BeforeAll static void migrate(){
        var data=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();
        jdbc=JdbcClient.create(data);tx=new TransactionTemplate(new DataSourceTransactionManager(data));
    }
    @Test void pausesBacksUpAndPhysicallyDeletesOnlyOldPricesWithoutChangingHistoricalQuote() throws Exception {
        var objects=new HashMap<String,byte[]>();var storage=mock(AssetStorageService.class);
        doAnswer(call->{objects.put(call.getArgument(0),((InputStream)call.getArgument(1)).readAllBytes());return null;}).when(storage).putRaw(anyString(),any(),anyLong(),anyString());
        when(storage.openRaw(anyString())).thenAnswer(call->new ByteArrayInputStream(Objects.requireNonNull(objects.get(call.getArgument(0)))));
        when(storage.removeRaw(anyString())).thenAnswer(call->{objects.remove(call.getArgument(0));return true;});
        tx.executeWithoutResult(status->{try{
            var guard=new LogisticsDatasetGuard(jdbc);var directory=new CompanyChannelService(jdbc,mapper);
            var datasets=new LogisticsDatasetService(jdbc,mapper,guard,storage);
            var rebuild=new CompanyPriceRebuildService(jdbc,mapper,directory,datasets,storage);
            var old=guard.activeId();var provider=UUID.randomUUID();var channel=UUID.randomUUID();var version=UUID.randomUUID();var quote=UUID.randomUUID();
            jdbc.sql("insert into logistics_provider(id,dataset_id,code,payload,created_at,updated_at) values(:id,:d,'OLD','{\"name\":\"花海\",\"enabled\":true}',now(),now())").param("id",provider).param("d",old).update();
            jdbc.sql("insert into logistics_channel(id,dataset_id,provider_id,code,rule_id,payload,created_at,updated_at) values(:id,:d,:p,'OLD',999,'{\"name\":\"旧渠道\"}',now(),now())").param("id",channel).param("d",old).param("p",provider).update();
            jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at) values(:id,:c,1,'published','old','{\"rows\":[],\"oldPrice\":123}',now())").param("id",version).param("c",channel).update();
            jdbc.sql("update logistics_channel set current_version_id=:v where id=:c").param("v",version).param("c",channel).update();
            var history=mapper.createObjectNode().put("amount",123.45);history.putArray("quoteOptions").addObject().put("logisticsVersionId",version.toString()).put("freightCny",12.34).put("surchargeUsd",2);
            jdbc.sql("insert into quotation_record values(:id,'COMPANY-QA','QA','pending',cast(:p as jsonb),0,now(),now())").param("id",quote).param("p",history.toString()).update();
            jdbc.sql("insert into quotation_draft values('COMPANY-QA','{\"userInput\":\"保留\"}',0,now())").update();
            String before=jdbc.sql("select payload::text from quotation_record where id=:id").param("id",quote).query(String.class).single();
            var preview=rebuild.preview();assertEquals(1,preview.path("priceVersions").asInt());
            var job=rebuild.begin(preview.deepCopy().put("note","真实事务测试"),"QA");var id=UUID.fromString(job.path("id").asText());
            assertTrue(directory.state(false).path("paused").asBoolean());assertThrows(AppException.class,()->guard.writable(old));
            assertThrows(AppException.class,()->rebuild.purge(id,mapper.createObjectNode().put("deleteConfirmed",true)));
            var saved=rebuild.backup(id);assertEquals("backed-up",saved.path("phase").asText());
            var backupKey=saved.path("payload").path("backup").path("objectKey").asText();assertTrue(objects.containsKey(backupKey));
            assertEquals(1,mapper.readTree(objects.get(backupKey)).path("logistics_version").size());
            assertThrows(AppException.class,()->rebuild.purge(id,mapper.createObjectNode()));
            assertEquals("deleted",rebuild.purge(id,mapper.createObjectNode().put("deleteConfirmed",true)).path("phase").asText());
            assertEquals(0,jdbc.sql("select count(*) from logistics_version").query(Integer.class).single());
            assertEquals(before,jdbc.sql("select payload::text from quotation_record where id=:id").param("id",quote).query(String.class).single());
            assertEquals(1,jdbc.sql("select jsonb_array_length(snapshot->'versions') from logistics_quotation_history where quotation_id=:id").param("id",quote).query(Integer.class).single());
            assertTrue(jdbc.sql("select (payload->>'logisticsRepriceRequired')::boolean from quotation_draft where owner_account='COMPANY-QA'").query(Boolean.class).single());
            assertEquals("deleted",rebuild.purge(id,mapper.createObjectNode().put("deleteConfirmed",true)).path("phase").asText());
            rebuild.cleanupObjects(id);assertTrue(objects.containsKey(backupKey));
            assertThrows(AppException.class,()->rebuild.finish(id,mapper.createObjectNode().put("reviewConfirmed",true).put("note","缺少基准不能恢复"),"QA"));
            assertTrue(directory.state(false).path("paused").asBoolean());
            assertThrows(AppException.class,()->rebuild.restore(id,mapper.createObjectNode().put("note","未确认"),"QA"));
            assertEquals("restored",rebuild.restore(id,mapper.createObjectNode().put("note","显式恢复验证").put("restoreConfirmed",true),"QA").path("phase").asText());
            assertFalse(directory.state(false).path("paused").asBoolean());
            assertEquals(1,jdbc.sql("select count(*) from logistics_version").query(Integer.class).single());
            assertEquals(before,jdbc.sql("select payload::text from quotation_record where id=:id").param("id",quote).query(String.class).single());
        }finally{status.setRollbackOnly();}});
    }
}
