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
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Test void upgradesProductionV26DataToV31WithoutChangingBusinessPayloads(){
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
        assertEquals("31",jdbc.sql("select version from flyway_schema_history where success order by installed_rank desc limit 1").query(String.class).single());
        assertEquals(legacy,jdbc.sql("select dataset_id from logistics_provider where id=:id").param("id",provider).query(UUID.class).single());
        assertEquals(legacy,jdbc.sql("select dataset_id from logistics_channel where id=:id").param("id",channel).query(UUID.class).single());
        assertEquals(version,jdbc.sql("select current_version_id from logistics_channel where id=:id").param("id",channel).query(UUID.class).single());
        assertEquals(12.34,jdbc.sql("select (payload->'rows'->0->>'pricePerKg')::numeric from logistics_version where id=:id").param("id",version).query(Double.class).single());
        assertEquals("LEGACY-C",jdbc.sql("select payload->'allowedChannelCodes'->>0 from finance_setting where setting_key='logistics-test'").query(String.class).single());
        assertEquals(17.34,jdbc.sql("select (payload->>'logisticsFee')::numeric from quotation_record where id=:id").param("id",quotation).query(Double.class).single());
        assertTrue(ready(jdbc,version));
        assertEquals(1,jdbc.sql("select count(*) from logistics_billing_acceptance where version_id=:id and kind='legacy'").param("id",version).query(Integer.class).single());
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
    }
    static void resetDatabase(){Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).cleanDisabled(false).load().clean();}
    static boolean ready(JdbcClient jdbc,UUID id){return jdbc.sql("select logistics_version_quote_ready(:id)").param("id",id).query(Boolean.class).single();}
    static UUID seed(JdbcClient jdbc,UUID dataset,String status,boolean quoteReady,int rule){
        var p=UUID.randomUUID();var c=UUID.randomUUID();var v=UUID.randomUUID();
        jdbc.sql("insert into logistics_provider(id,dataset_id,code,payload,created_at,updated_at) values(:id,:d,:code,'{}',now(),now())").param("id",p).param("d",dataset).param("code",p.toString()).update();
        jdbc.sql("insert into logistics_channel(id,dataset_id,provider_id,code,rule_id,payload,created_at,updated_at) values(:id,:d,:p,:code,:rule,'{}',now(),now())").param("id",c).param("d",dataset).param("p",p).param("code",c.toString()).param("rule",rule).update();
        jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at) values(:id,:c,1,:status,'test',jsonb_build_object('quoteReady',:ready,'rows','[]'::jsonb),now())").param("id",v).param("c",c).param("status",status).param("ready",quoteReady).update();
        jdbc.sql("update logistics_channel set current_version_id=:v where id=:c").param("v",v).param("c",c).update();return v;
    }
}
