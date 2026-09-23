package com.milano.quotation.logistics;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class LogisticsAcceptanceMigrationTest {
    @Test void partialBatchCoverageIsCheckedAgainstCurrentDatabaseBeforePublishing() {
        resetDatabase();
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();
        var dataSource=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var jdbc=JdbcClient.create(dataSource);var mapper=new tools.jackson.databind.ObjectMapper();
        var dataset=UUID.fromString("00000000-0000-0000-0000-000000000001");
        var old=seed(jdbc,dataset,"published",true,901);
        var draft=seed(jdbc,dataset,"draft",true,902);
        var draftChannel=jdbc.sql("select channel_id from logistics_version where id=:id").param("id",draft).query(UUID.class).single();
        jdbc.sql("update logistics_provider set payload='{"+"\"name\":\"燕文\"}'::jsonb").update();
        jdbc.sql("update logistics_channel set payload='{"+"\"name\":\"旧化妆品渠道\",\"enabled\":true}'::jsonb").update();
        var payload=mapper.createObjectNode();payload.putArray("results").addObject().put("channelId",draftChannel.toString()).put("versionId",draft.toString()).put("providerName","燕文").put("channelName","新增渠道");
        var batch=UUID.randomUUID();
        jdbc.sql("insert into logistics_import_batch(id,dataset_id,requested_by,request_key,status,phase,payload) values(:id,:dataset,'tester',:key,'completed','review',cast(:payload as jsonb))")
            .param("id",batch).param("dataset",dataset).param("key",batch.toString()).param("payload",payload.toString()).update();
        var logistics=org.mockito.Mockito.mock(LogisticsService.class);var billing=org.mockito.Mockito.mock(LogisticsBillingAcceptanceService.class);
        var service=new LogisticsBatchPublishService(jdbc,mapper,logistics,billing,new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        var coverage=service.coverage(batch);assertEquals(1,coverage.path("missingCount").asInt());
        var request=mapper.createObjectNode().put("note","核对局部更新");request.set("selections",payload.path("results"));
        assertThrows(com.milano.quotation.common.AppException.class,()->service.publishReady(batch,request,"tester"));
        org.mockito.Mockito.verifyNoInteractions(logistics,billing);
        request.put("partialUpdateConfirmed",true).put("coverageToken",coverage.path("token").asText());
        // A concurrent rename/source change must force a new coverage review before any price write.
        jdbc.sql("update logistics_version set payload=payload || '{\"fileName\":\"new-source.xlsx\"}'::jsonb where id=:id").param("id",old).update();
        assertThrows(com.milano.quotation.common.AppException.class,()->service.publishReady(batch,request,"tester"));
        org.mockito.Mockito.verifyNoInteractions(logistics,billing);
        request.put("coverageToken",service.coverage(batch).path("token").asText());
        org.mockito.Mockito.when(logistics.publishReviewed(org.mockito.ArgumentMatchers.eq(draftChannel),org.mockito.ArgumentMatchers.eq(draft),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyBoolean(),org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(mapper.createObjectNode());
        var result=service.publishReady(batch,request,"tester");assertEquals(1,result.path("publishedCount").asInt());assertTrue(result.path("partialUpdateConfirmed").asBoolean());
        assertEquals(1,result.path("coverage").path("missingCount").asInt());
        org.mockito.Mockito.verify(billing).approveValidatedImport(org.mockito.ArgumentMatchers.eq(draft),org.mockito.ArgumentMatchers.eq("tester"),org.mockito.ArgumentMatchers.anyString());
        jdbc.sql("update logistics_channel set payload=jsonb_set(payload,'{enabled}','false') where current_version_id=:id").param("id",old).update();
        assertFalse(service.coverage(batch).path("partial").asBoolean(),"Disabled routes do not block active-channel updates");
    }
    @Test void kuwaitRoundingProjectionUpgradesExistingRowsWithoutChangingPricesOrApprovals() {
        resetDatabase();
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("46").load().migrate();
        var jdbc=JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        var mapper=new tools.jackson.databind.ObjectMapper();
        var version=seed(jdbc,UUID.fromString("00000000-0000-0000-0000-000000000001"),"published",true,590);
        var row=new LogisticsKuwaitCosmeticsPricingTest().row();
        var rows=mapper.createArrayNode().add(row).add(row.deepCopy().put("countryCode","QA").put("areaName","卡塔尔"));
        jdbc.sql("update logistics_version set payload=jsonb_set(payload,'{rows}',cast(:rows as jsonb)) where id=:id")
            .param("id",version).param("rows",rows.toString()).update();
        jdbc.sql("insert into logistics_billing_acceptance(id,version_id,rows_fingerprint,engine_version,kind,payload,reviewed_by) select :id,id,rows_fingerprint,'logistics-billing-v7','validated-import','{}','QA' from logistics_version where id=:version")
            .param("id",UUID.randomUUID()).param("version",version).update();
        var before=jdbc.sql("select payload::text || rows_fingerprint from logistics_version where id=:id").param("id",version).query(String.class).single();
        assertFalse(jdbc.sql("select jsonb_exists(quote_rows->0,'billingStepKg') from logistics_version where id=:id").param("id",version).query(Boolean.class).single());
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();
        assertEquals(before,jdbc.sql("select payload::text || rows_fingerprint from logistics_version where id=:id").param("id",version).query(String.class).single());
        assertTrue(ready(jdbc,version));
        assertEquals(1,jdbc.sql("select count(*) from logistics_billing_acceptance where version_id=:id").param("id",version).query(Integer.class).single());
        var queries=new LogisticsQueryService(jdbc,mapper);
        var projected=queries.publishedRules(null,"普货",java.util.List.of("KW","QA"),java.util.List.of()).rules().getFirst().path("prices");
        var engine=new LogisticsBillingEngine(mapper);
        for(var value:projected) {
            assertEquals(.1,value.path("billingStepKg").asDouble());
            assertEquals("云途全球化妆品类专线挂号",value.path("sourceSheet").asText());
        }
        assertEquals(89.8,engine.calculate(projected,mapper.createObjectNode().put("country","KW").put("weightKg",.12)).path("total").asDouble());
        assertEquals(83.88,engine.calculate(projected,mapper.createObjectNode().put("country","QA").put("weightKg",.12)).path("total").asDouble());
        jdbc.sql("update logistics_billing_acceptance set engine_version='logistics-billing-v8' where version_id=:id").param("id",version).update();
        assertTrue(ready(jdbc,version));
    }
    @Test void halfKilogramParcelProjectionRequiresValidMinimumAndNewAcceptance() {
        resetDatabase();
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();
        var jdbc=JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        var mapper=new tools.jackson.databind.ObjectMapper();
        var row=mapper.createObjectNode().put("countryCode","US").put("areaName","美国").put("pricingModel","per-piece-500g")
            .put("weightFromKg",0).put("weightToKg",.5).put("weightFromInclusive",false).put("weightToInclusive",true)
            .put("minChargeWeightKg",.5).put("intervalPrice",91).put("pricePerKg",0).put("registrationFee",0);
        assertTrue(jdbc.sql("select logistics_price_row_quote_supported(cast(:row as jsonb))").param("row",row.toString()).query(Boolean.class).single());
        for(var invalid:java.util.List.of(row.deepCopy().put("minChargeWeightKg",.05),row.deepCopy().put("weightToKg",.6),
            row.deepCopy().put("intervalPrice",-91),row.deepCopy().put("intervalPrice","91"),row.deepCopy().put("pricingModel","interval"),
            row.deepCopy().put("pricePerKg",91),row.deepCopy().put("minChargeWeightKg","wrong"),row.deepCopy().put("weightFromInclusive",true)))
            assertFalse(jdbc.sql("select logistics_price_row_quote_supported(cast(:row as jsonb))").param("row",invalid.toString()).query(Boolean.class).single(),invalid.toString());
        var dataset=UUID.fromString("00000000-0000-0000-0000-000000000001");
        var version=seed(jdbc,dataset,"published",true,1);
        jdbc.sql("update logistics_version set payload=jsonb_set(payload,'{rows}',cast(:rows as jsonb)) where id=:id")
            .param("id",version).param("rows",mapper.createArrayNode().add(row).toString()).update();
        assertEquals(91,jdbc.sql("select (quote_rows->0->>'intervalPrice')::int from logistics_version where id=:id").param("id",version).query(Integer.class).single());
        jdbc.sql("insert into logistics_billing_acceptance(id,version_id,rows_fingerprint,engine_version,kind,payload,reviewed_by) select :id,id,rows_fingerprint,'logistics-billing-v5','validated-import','{}','QA' from logistics_version where id=:version")
            .param("id",UUID.randomUUID()).param("version",version).update();
        assertFalse(ready(jdbc,version));
        jdbc.sql("update logistics_billing_acceptance set engine_version='logistics-billing-v6' where version_id=:id").param("id",version).update();
        assertTrue(ready(jdbc,version));
        var queries=new LogisticsQueryService(jdbc,mapper);
        var projected=queries.publishedRules(null,"普货",java.util.List.of("US"),java.util.List.of()).rules().getFirst().path("prices").get(0);
        assertEquals("per-piece-500g",projected.path("pricingModel").asText());assertEquals(.5,projected.path("minChargeWeightKg").asDouble());assertEquals(91,projected.path("intervalPrice").asInt());
        jdbc.sql("update logistics_version set payload=jsonb_set(payload,'{rows,0,intervalPrice}','92'::jsonb) where id=:id").param("id",version).update();
        assertFalse(ready(jdbc,version),"Updated parcel prices require fresh acceptance");
        var gramVersion=seed(jdbc,dataset,"published",true,2);
        var gram=row.deepCopy().put("pricingModel","per-kg-1g").put("intervalPrice",0).put("pricePerKg",100).put("minChargeWeightKg",.05);
        jdbc.sql("update logistics_version set payload=jsonb_set(payload,'{rows}',cast(:rows as jsonb)) where id=:id")
            .param("id",gramVersion).param("rows",mapper.createArrayNode().add(gram).toString()).update();
        jdbc.sql("insert into logistics_billing_acceptance(id,version_id,rows_fingerprint,engine_version,kind,payload,reviewed_by) select :id,id,rows_fingerprint,'logistics-billing-v6','validated-import','{}','QA' from logistics_version where id=:version")
            .param("id",UUID.randomUUID()).param("version",gramVersion).update();
        assertFalse(ready(jdbc,gramVersion));
        jdbc.sql("update logistics_billing_acceptance set engine_version='logistics-billing-v7' where version_id=:id").param("id",gramVersion).update();
        assertTrue(ready(jdbc,gramVersion));
        var projectedGram=queries.publishedRules(null,"普货",java.util.List.of("US"),java.util.List.of()).rules().getFirst().path("prices").get(0);
        assertEquals("per-kg-1g",projectedGram.path("pricingModel").asText());
        assertEquals(.051,new LogisticsBillingEngine(mapper).calculate(mapper.createArrayNode().add(projectedGram),mapper.createObjectNode().put("country","US").put("weightKg",.050001)).path("chargeWeightKg").asDouble());
    }
    @Test void minimumProjectionRejectsInvalidFloorsAndKeepsValidZero() {
        resetDatabase();
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();
        var jdbc=JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        for(var minimum:java.util.List.of("-0.1","0.11","\"invalid\"")) {
            var row="{\"pricePerKg\":63,\"weightToKg\":0.1,\"minChargeWeightKg\":"+minimum+"}";
            assertFalse(jdbc.sql("select logistics_price_row_quote_supported(cast(:row as jsonb))").param("row",row).query(Boolean.class).single());
        }
        for(var minimum:java.util.List.of("0","0.03","0.1","null")) {
            var row="{\"pricePerKg\":63,\"weightToKg\":0.1,\"minChargeWeightKg\":"+minimum+"}";
            assertTrue(jdbc.sql("select logistics_price_row_quote_supported(cast(:row as jsonb))").param("row",row).query(Boolean.class).single());
        }
    }
    @Test void weightRangePolicyExcludesHistoricalFirstNextWithoutChangingRowsOrApprovals(){
        resetDatabase();
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("34").load().migrate();
        var jdbc=JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        var dataset=UUID.fromString("00000000-0000-0000-0000-000000000001");
        var normal=seed(jdbc,dataset,"published",true,1);var firstNext=seed(jdbc,dataset,"published",true,2);
        var mixed=seed(jdbc,dataset,"published",true,3);
        jdbc.sql("update logistics_version set payload=jsonb_set(payload,'{rows}',cast(:rows as jsonb)) where id=:id")
            .param("id",firstNext).param("rows","[{\"pricingModel\":\"first-next\",\"firstWeightKg\":0.5,\"firstWeightPrice\":20,\"nextWeightKg\":0.5,\"nextWeightPrice\":5}]").update();
        jdbc.sql("update logistics_version set payload=jsonb_set(payload,'{rows}',(payload->'rows')||cast(:extra as jsonb)) where id=:id")
            .param("id",mixed).param("extra","[{\"countryCode\":\"JP\",\"areaName\":\"日本\",\"pricingModel\":\"first-next\",\"firstWeightKg\":0.5,\"firstWeightPrice\":20,\"nextWeightKg\":0.5,\"nextWeightPrice\":5}]").update();
        for(var version:java.util.List.of(normal,firstNext,mixed))jdbc.sql("insert into logistics_billing_acceptance(id,version_id,rows_fingerprint,engine_version,kind,payload,reviewed_by) select :id,id,rows_fingerprint,'logistics-billing-v3','verified','{}','QA' from logistics_version where id=:version")
            .param("id",UUID.randomUUID()).param("version",version).update();
        assertTrue(ready(jdbc,normal));assertTrue(ready(jdbc,firstNext));
        var before=jdbc.sql("select payload::text from logistics_version where id=:id").param("id",firstNext).query(String.class).single();
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();
        assertTrue(ready(jdbc,normal));assertFalse(ready(jdbc,firstNext));
        assertEquals(before,jdbc.sql("select payload::text from logistics_version where id=:id").param("id",firstNext).query(String.class).single());
        assertTrue(ready(jdbc,mixed));assertEquals(3,jdbc.sql("select count(*) from logistics_billing_acceptance").query(Integer.class).single());
        var queries=new LogisticsQueryService(jdbc,new tools.jackson.databind.ObjectMapper());
        assertEquals(2,queries.manifest().publishedChannels());assertEquals(2,queries.publishedCatalog(null).rules().size());
        assertTrue(queries.manifest().countries().stream().noneMatch(country->country.code().equals("JP")));
        for(var rule:queries.publishedRules(null,"普货",java.util.List.of("US","JP"),java.util.List.of()).rules()) {
            assertEquals(1,rule.path("prices").size());assertEquals("US",rule.path("prices").get(0).path("countryCode").asText());
        }
    }
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Test void upgradesProductionV26DataToV36WithoutChangingBusinessPayloads(){
        resetDatabase();
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("26").load().migrate();
        var jdbc=JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        var provider=UUID.randomUUID();var channel=UUID.randomUUID();var version=UUID.randomUUID();var quotation=UUID.randomUUID();
        jdbc.sql("insert into logistics_provider(id,code,payload,created_at,updated_at) values(:id,'LEGACY-P','{\"name\":\"旧物流商\"}'::jsonb,now(),now())").param("id",provider).update();
        jdbc.sql("insert into logistics_channel(id,provider_id,code,rule_id,payload,created_at,updated_at) values(:id,:provider,'LEGACY-C',991,'{\"name\":\"旧渠道\"}'::jsonb,now(),now())").param("id",channel).param("provider",provider).update();
        jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at,published_at) values(:id,:channel,1,'published','legacy-hash','{\"quoteReady\":true,\"rows\":[{\"countryCode\":\"US\",\"weightFromKg\":0,\"weightToKg\":1,\"pricePerKg\":12.34,\"registrationFee\":5}]}'::jsonb,now(),now())").param("id",version).param("channel",channel).update();
        jdbc.sql("update logistics_channel set current_version_id=:version where id=:channel").param("version",version).param("channel",channel).update();
        jdbc.sql("insert into finance_setting(setting_key,payload,updated_at) values('logistics-test','{\"allowedChannelCodes\":[\"LEGACY-C\"]}'::jsonb,now())").update();
        jdbc.sql("insert into quotation_record(id,quote_no,owner_account,status,payload,created_at,updated_at) values(:id,'Q-MIGRATION-26','ADMIN','draft','{\"logisticsChannelCode\":\"LEGACY-C\",\"logisticsFee\":17.34}'::jsonb,now(),now())").param("id",quotation).update();

        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();

        var legacy=UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals("47",jdbc.sql("select version from flyway_schema_history where success order by installed_rank desc limit 1").query(String.class).single());
        assertEquals(legacy,jdbc.sql("select dataset_id from logistics_provider where id=:id").param("id",provider).query(UUID.class).single());
        assertEquals(legacy,jdbc.sql("select dataset_id from logistics_channel where id=:id").param("id",channel).query(UUID.class).single());
        assertEquals(version,jdbc.sql("select current_version_id from logistics_channel where id=:id").param("id",channel).query(UUID.class).single());
        assertEquals(12.34,jdbc.sql("select (payload->'rows'->0->>'pricePerKg')::numeric from logistics_version where id=:id").param("id",version).query(Double.class).single());
        assertEquals("LEGACY-C",jdbc.sql("select payload->'allowedChannelCodes'->>0 from finance_setting where setting_key='logistics-test'").query(String.class).single());
        assertEquals(17.34,jdbc.sql("select (payload->>'logisticsFee')::numeric from quotation_record where id=:id").param("id",quotation).query(Double.class).single());
        assertTrue(ready(jdbc,version));
        assertEquals(1,jdbc.sql("select count(*) from logistics_billing_acceptance where version_id=:id and kind='legacy'").param("id",version).query(Integer.class).single());
        assertEquals(1,jdbc.sql("select row_count from logistics_version where id=:id").param("id",version).query(Integer.class).single());
        assertEquals(0,jdbc.sql("select issue_count from logistics_version where id=:id").param("id",version).query(Integer.class).single());
        assertFalse(jdbc.sql("select jsonb_exists(workspace_payload,'rows') from logistics_version where id=:id").param("id",version).query(Boolean.class).single());
        var originalFingerprint=jdbc.sql("select rows_fingerprint from logistics_version where id=:id").param("id",version).query(String.class).single();
        jdbc.sql("update logistics_version set payload=jsonb_set(payload,'{rows,0,pricePerKg}','13.00'::jsonb) where id=:id").param("id",version).update();
        assertNotEquals(originalFingerprint,jdbc.sql("select rows_fingerprint from logistics_version where id=:id").param("id",version).query(String.class).single());
        assertFalse(ready(jdbc,version),"Changing rows must invalidate the previous billing acceptance");
    }
    @Test void migratesOnlyExistingOriginalLibraryVersionsWithoutSwitchingData(){
        resetDatabase();
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("27").load().migrate();
        var jdbc=JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        var original=UUID.fromString("00000000-0000-0000-0000-000000000001");var next=UUID.randomUUID();
        jdbc.sql("insert into logistics_dataset(id,name,status,created_by) values(:id,'准备测试','preparing','QA')").param("id",next).update();
        var old=seed(jdbc,original,"published",true,1);var draft=seed(jdbc,original,"draft",true,2);var pending=seed(jdbc,original,"published",false,3);var fresh=seed(jdbc,next,"published",true,4);
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();
        assertEquals(1,jdbc.sql("select count(*) from logistics_billing_acceptance").query(Integer.class).single());
        assertTrue(ready(jdbc,old));assertFalse(ready(jdbc,draft));assertFalse(ready(jdbc,pending));assertFalse(ready(jdbc,fresh));
        assertEquals(original,jdbc.sql("select id from logistics_dataset where status='active'").query(UUID.class).single());
        assertFalse(ready(jdbc,seed(jdbc,original,"published",true,5)),"New old-library prices also need new acceptance");
        var validated=seed(jdbc,original,"published",true,6);var fingerprint=jdbc.sql("select rows_fingerprint from logistics_version where id=:id").param("id",validated).query(String.class).single();
        jdbc.sql("insert into logistics_billing_acceptance(id,version_id,rows_fingerprint,engine_version,kind,payload,reviewed_by) values(:id,:version,:hash,'logistics-billing-v3','validated-import','{}','tester')")
                .param("id",UUID.randomUUID()).param("version",validated).param("hash",fingerprint).update();
        assertTrue(ready(jdbc,validated));
        assertEquals(1,jdbc.sql("select count(*) from information_schema.tables where table_schema='public' and table_name='logistics_import_file'").query(Integer.class).single());
    }
    static void resetDatabase(){Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).cleanDisabled(false).load().clean();}
    static boolean ready(JdbcClient jdbc,UUID id){return jdbc.sql("select logistics_version_quote_ready(:id)").param("id",id).query(Boolean.class).single();}
    static UUID seed(JdbcClient jdbc,UUID dataset,String status,boolean quoteReady,int rule){
        var p=UUID.randomUUID();var c=UUID.randomUUID();var v=UUID.randomUUID();
        jdbc.sql("insert into logistics_provider(id,dataset_id,code,payload,created_at,updated_at) values(:id,:d,:code,'{}',now(),now())").param("id",p).param("d",dataset).param("code",p.toString()).update();
        jdbc.sql("insert into logistics_channel(id,dataset_id,provider_id,code,rule_id,payload,created_at,updated_at) values(:id,:d,:p,:code,:rule,'{}',now(),now())").param("id",c).param("d",dataset).param("p",p).param("code",c.toString()).param("rule",rule).update();
        jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at) values(:id,:c,1,:status,'test',jsonb_build_object('quoteReady',:ready,'rows',jsonb_build_array(jsonb_build_object('pricingModel','per-kg','pricePerKg',10,'registrationFee',2,'areaName','美国','countryCode','US'))),now())").param("id",v).param("c",c).param("status",status).param("ready",quoteReady).update();
        jdbc.sql("update logistics_channel set current_version_id=:v where id=:c").param("v",v).param("c",c).update();return v;
    }
}
