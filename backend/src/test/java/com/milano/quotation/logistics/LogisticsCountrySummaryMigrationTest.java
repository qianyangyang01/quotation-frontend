package com.milano.quotation.logistics;

import com.milano.quotation.storage.AssetStorageService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class LogisticsCountrySummaryMigrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");

    @Test void backfillsAndMaintainsCountsWithoutChangingSnapshotsAndKeepsEvidenceOutOfUi() {
        var config = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");
        config.target("54").load().migrate();
        var jdbc = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        var provider = UUID.randomUUID(); var channel = UUID.randomUUID(); var version = UUID.randomUUID();
        jdbc.sql("insert into logistics_provider(id,dataset_id,code,payload,created_at,updated_at) values(:id,logistics_active_dataset(),'SUMMARY','{}',now(),now())").param("id",provider).update();
        jdbc.sql("insert into logistics_channel(id,dataset_id,provider_id,code,rule_id,payload,created_at,updated_at) values(:id,logistics_active_dataset(),:provider,'SUMMARY',900001,'{}',now(),now())")
                .param("id",channel).param("provider",provider).update();
        jdbc.sql("""
                insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at)
                values(:id,:channel,1,'draft','source-hash',
                '{"fileName":"source.xlsx","importedAt":"2026-10-01","errors":1,"summary":{"added":5},
                  "missingEtaRoutes":[{"sourceRow":100}],"reviewWarningRows":[{"sourceRow":200}],
                  "rows":[{"countryCode":"US","areaName":"美国"},{"countryCode":"US","areaName":"美国"},
                    {"countryCode":"","areaName":"德国"},{"countryCode":null,"areaName":"德国"},{}]}'::jsonb,now())
                """).param("id",version).param("channel",channel).update();
        jdbc.sql("insert into quotation_record(id,quote_no,owner_account,status,payload,created_at,updated_at) values(:id,'KEEP-SUMMARY','QA','pending','{\"freight\":23,\"reviewStatus\":\"approved\"}',now(),now())")
                .param("id",UUID.randomUUID()).update();
        var before = jdbc.sql("select md5(string_agg(to_jsonb(q)::text,',' order by id)) from quotation_record q").query(String.class).single();
        var versionBefore = jdbc.sql("select payload::text from logistics_version where id=:id").param("id",version).query(String.class).single();
        assertEquals(1, config.target("55").load().migrate().migrationsExecuted);
        assertEquals(2, jdbc.sql("select country_count from logistics_version where id=:id").param("id",version).query(Integer.class).single());
        assertEquals(versionBefore, jdbc.sql("select payload::text from logistics_version where id=:id").param("id",version).query(String.class).single());
        assertEquals(before, jdbc.sql("select md5(string_agg(to_jsonb(q)::text,',' order by id)) from quotation_record q").query(String.class).single());
        var service = new LogisticsDatasetService(jdbc,new ObjectMapper(),new LogisticsDatasetGuard(jdbc),mock(AssetStorageService.class));
        var dataset = jdbc.sql("select logistics_active_dataset()").query(UUID.class).single();
        var full = service.workspace(dataset).path("versions").get(0);
        var compact = service.workspaceSummary(dataset).path("versions").get(0);
        assertTrue(full.has("missingEtaRoutes"));
        assertFalse(compact.has("missingEtaRoutes"));
        assertFalse(compact.has("reviewWarningRows"));
        for (var field : new String[]{"id","channelId","status","versionNumber","fileName","importedAt","rowCount","countryCount","quoteReady","errors","summary"})
            assertEquals(full.path(field),compact.path(field),field);
        jdbc.sql("update logistics_version set payload=jsonb_set(payload,'{rows}','[{\"countryCode\":\"GB\",\"areaName\":\"英国\"}]'::jsonb) where id=:id").param("id",version).update();
        assertEquals(1,jdbc.sql("select country_count from logistics_version where id=:id").param("id",version).query(Integer.class).single());
        jdbc.sql("update logistics_version set payload=payload-'rows' where id=:id").param("id",version).update();
        assertEquals(0,jdbc.sql("select country_count from logistics_version where id=:id").param("id",version).query(Integer.class).single());
    }
}
