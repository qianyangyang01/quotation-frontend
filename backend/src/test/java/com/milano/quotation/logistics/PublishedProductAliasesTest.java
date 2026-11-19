package com.milano.quotation.logistics;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class PublishedProductAliasesTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Test void onlyCurrentPublishedBoundSourceNamesBecomeReadOnlyImportAliases() {
        var mapper=new ObjectMapper();var jdbc=JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        for(var ddl:List.of("create table logistics_company_state(singleton boolean,enabled boolean,revision int)",
                "create table logistics_company_channel(id uuid,payload jsonb)","create table logistics_company_binding(channel_id uuid,company_channel_id uuid)",
                "create table logistics_channel(id uuid,dataset_id uuid,current_version_id uuid)","create table logistics_dataset(id uuid,status text)",
                "create table logistics_version(id uuid,status text,payload jsonb)","create table logistics_import_batch(id uuid,payload jsonb)"))jdbc.sql(ddl).update();
        var company=UUID.randomUUID();var channel=UUID.randomUUID();var dataset=UUID.randomUUID();var version=UUID.randomUUID();var batch=UUID.randomUUID();
        String sourceName="美国标准DDP专线-普货A(SP)\n美国标准DDP专线-普货C(SP)";
        var entry=mapper.createObjectNode().put("id",company.toString()).put("providerName","容鼎").put("channelName","美国纯商派DDP专线-普货").put("enabled",true).put("logisticsAttribute","普货");
        entry.putArray("aliases");entry.putArray("productCodes").add("OLD-CODE");
        jdbc.sql("insert into logistics_company_state values(true,true,27)").update();
        jdbc.sql("insert into logistics_company_channel values(:id,cast(:p as jsonb))").param("id",company).param("p",entry.toString()).update();
        jdbc.sql("insert into logistics_company_binding values(:c,:id)").param("c",channel).param("id",company).update();
        jdbc.sql("insert into logistics_dataset values(:id,'active')").param("id",dataset).update();
        jdbc.sql("insert into logistics_channel values(:id,:d,:v)").param("id",channel).param("d",dataset).param("v",version).update();
        var payload=mapper.createObjectNode().put("batchId",batch.toString()).put("sourceFileIndex",0).put("companyChannelId",company.toString());
        jdbc.sql("insert into logistics_version values(:id,'published',cast(:p as jsonb))").param("id",version).param("p",payload.toString()).update();
        var imported=mapper.createObjectNode();var report=imported.putArray("fileReports").addObject().put("fileIndex",0);
        var matches=report.putArray("sheets").addObject().putArray("channelMatches");
        matches.addObject().put("status","matched").put("providerName","容鼎").put("companyChannelId",company.toString()).put("channelName",sourceName);
        matches.addObject().put("status","filtered").put("providerName","容鼎").put("companyChannelId",company.toString()).put("channelName","不应学习的渠道");
        jdbc.sql("insert into logistics_import_batch values(:id,cast(:p as jsonb))").param("id",batch).param("p",imported.toString()).update();
        var service=new CompanyChannelService(jdbc,mapper);var snapshot=service.snapshot();
        assertEquals(1,snapshot.path("entries").get(0).path("importAliases").size());
        assertTrue(new CompanyChannelScope(snapshot).matchSourceCell("容鼎",sourceName,"NEW-2027(A)\nNEW-2028(C)").accepted());
        assertFalse(new CompanyChannelScope(snapshot).matchSourceCell("容鼎",sourceName.replace("(SP)",""),"NEW-2027(A)\nNEW-2028(C)").accepted());
        for(var pair:List.of(
                new String[]{"update logistics_version set status='draft'","update logistics_version set status='published'"},
                new String[]{"update logistics_dataset set status='archived'","update logistics_dataset set status='active'"},
                new String[]{"update logistics_version set payload=jsonb_set(payload,'{sourceFileIndex}','1')","update logistics_version set payload=jsonb_set(payload,'{sourceFileIndex}','0')"},
                new String[]{"update logistics_import_batch set payload=jsonb_set(payload,'{fileReports,0,sheets,0,channelMatches,0,providerName}','\"其他商\"')","update logistics_import_batch set payload=jsonb_set(payload,'{fileReports,0,sheets,0,channelMatches,0,providerName}','\"容鼎\"')"})) {
            jdbc.sql(pair[0]).update();assertFalse(service.snapshot().path("entries").get(0).has("importAliases"));jdbc.sql(pair[1]).update();
        }
        assertEquals(0,jdbc.sql("select jsonb_array_length(payload->'aliases') from logistics_company_channel").query(Integer.class).single());
        assertFalse(jdbc.sql("select payload->'importAliases' is not null from logistics_company_channel").query(Boolean.class).single());
        jdbc.sql("delete from logistics_company_binding").update();assertFalse(service.snapshot().path("entries").get(0).has("importAliases"));
    }
}
