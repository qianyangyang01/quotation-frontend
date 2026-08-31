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
    @Test void migratesOnlyExistingOriginalLibraryVersionsWithoutSwitchingData(){
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
    static boolean ready(JdbcClient jdbc,UUID id){return jdbc.sql("select logistics_version_quote_ready(:id)").param("id",id).query(Boolean.class).single();}
    static UUID seed(JdbcClient jdbc,UUID dataset,String status,boolean quoteReady,int rule){
        var p=UUID.randomUUID();var c=UUID.randomUUID();var v=UUID.randomUUID();
        jdbc.sql("insert into logistics_provider(id,dataset_id,code,payload,created_at,updated_at) values(:id,:d,:code,'{}',now(),now())").param("id",p).param("d",dataset).param("code",p.toString()).update();
        jdbc.sql("insert into logistics_channel(id,dataset_id,provider_id,code,rule_id,payload,created_at,updated_at) values(:id,:d,:p,:code,:rule,'{}',now(),now())").param("id",c).param("d",dataset).param("p",p).param("code",c.toString()).param("rule",rule).update();
        jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at) values(:id,:c,1,:status,'test',jsonb_build_object('quoteReady',:ready,'rows','[]'::jsonb),now())").param("id",v).param("c",c).param("status",status).param("ready",quoteReady).update();
        jdbc.sql("update logistics_channel set current_version_id=:v where id=:c").param("v",v).param("c",c).update();return v;
    }
}
