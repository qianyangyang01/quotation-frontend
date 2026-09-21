package com.milano.quotation.logistics;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class AdditionalCompanyChannelsTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    final ObjectMapper mapper=new ObjectMapper();
    @Test void migrationAddsOnlyDirectoryEntriesPreservesExistingDisabledChannelAndIsIdempotent() throws Exception {
        var flyway=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).cleanDisabled(false).target("44").load();
        flyway.clean();flyway.migrate();
        try(var connection=java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var query=connection.createStatement()) {
            var resource=getClass().getResourceAsStream("/logistics-repairs/additional-company-channels-20260921.json");assertNotNull(resource);
            var plan=mapper.readTree(resource.readAllBytes());resource.close();
            assertEquals(0,AdditionalCompanyChannels.apply(connection,plan),"Unconfigured fresh installations stay untouched");
            var existing=plan.path("entries").get(0).deepCopy();((tools.jackson.databind.node.ObjectNode)existing).put("enabled",false).put("logisticsAttribute","保留属性");
            try(var insert=connection.prepareStatement("insert into logistics_company_channel(id,enabled,payload,updated_by) values(?::uuid,false,?::jsonb,'existing')")) {
                insert.setString(1,existing.path("id").asText());insert.setString(2,existing.toString());insert.executeUpdate();
            }
            query.executeUpdate("update logistics_company_state set enabled=true,revision=7 where singleton");
            var bad=plan.deepCopy();((tools.jackson.databind.node.ObjectNode)bad.path("entries").get(1)).put("providerName",existing.path("providerName").asText()).put("channelName",existing.path("channelName").asText());
            assertThrows(Exception.class,()->AdditionalCompanyChannels.apply(connection,bad));
            assertEquals(1,scalar(query,"select count(*) from logistics_company_channel"));
            var fingerprints=new java.util.HashMap<String,String>();
            for(var table:java.util.List.of("logistics_company_binding","logistics_version","quotation_record","finance_setting"))fingerprints.put(table,fingerprint(query,table));
            Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();
            assertEquals(15,scalar(query,"select count(*) from logistics_company_channel"));
            assertEquals(1,scalar(query,"select count(*) from logistics_company_channel where not enabled and updated_by='existing'"));
            assertEquals(8,scalar(query,"select revision from logistics_company_state where singleton"));
            assertEquals(1,scalar(query,"select count(*) from logistics_company_revision where revision=8 and jsonb_array_length(payload->'entries')=15"));
            for(var entry:fingerprints.entrySet())assertEquals(entry.getValue(),fingerprint(query,entry.getKey()),entry.getKey());
            assertEquals(0,AdditionalCompanyChannels.apply(connection,plan));
            assertEquals(8,scalar(query,"select revision from logistics_company_state where singleton"));
            query.executeUpdate("update logistics_company_state set paused=true where singleton");
            assertThrows(IllegalStateException.class,()->AdditionalCompanyChannels.apply(connection,plan));
        }
    }
    private int scalar(java.sql.Statement query,String sql)throws Exception {try(var result=query.executeQuery(sql)){assertTrue(result.next());return result.getInt(1);}}
    private String fingerprint(java.sql.Statement query,String table)throws Exception {try(var result=query.executeQuery("select md5(coalesce(string_agg(t::text,',' order by t::text),'')) from "+table+" t")){assertTrue(result.next());return result.getString(1);}}
}
