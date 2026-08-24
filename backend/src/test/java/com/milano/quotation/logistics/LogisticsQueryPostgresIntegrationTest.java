package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class LogisticsQueryPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("quotation_prod").withUsername("quotation_app").withPassword("quotation_test_password");
    static JdbcClient jdbc;
    static LogisticsQueryService service;
    static UUID providerId;
    static UUID channelId;
    static UUID versionId;

    @BeforeAll
    static void setup() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        service = new LogisticsQueryService(jdbc, new ObjectMapper());
        providerId = UUID.randomUUID(); channelId = UUID.randomUUID(); versionId = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("insert into logistics_provider(id,code,payload,version,created_at,updated_at) values(:id,'YUNTU',cast(:payload as jsonb),0,:now,:now)")
                .param("id", providerId).param("payload", "{\"id\":\"" + providerId + "\",\"code\":\"YUNTU\",\"name\":\"云途\",\"enabled\":true}").param("now", now).update();
        jdbc.sql("insert into logistics_channel(id,provider_id,code,rule_id,current_version_id,payload,version,created_at,updated_at) values(:id,:provider,'YT-PH',1,null,cast(:payload as jsonb),0,:now,:now)")
                .param("id", channelId).param("provider", providerId).param("payload", "{\"id\":\"" + channelId + "\",\"providerId\":\"" + providerId + "\",\"name\":\"云途普货\",\"code\":\"YT-PH\",\"type\":\"专线\",\"logisticsAttribute\":\"普货\",\"enabled\":true,\"createdAt\":\"2026-08-24T00:00:00Z\",\"updatedAt\":\"2026-08-24T00:00:00Z\"}").param("now", now).update();
        var rows = "[{\"areaName\":\"美国\",\"countryCode\":\"US\",\"weightFromKg\":0,\"weightToKg\":2,\"pricePerKg\":55,\"allowedMarks\":\"\",\"prohibitedMarks\":\"\"},{\"areaName\":\"德国\",\"countryCode\":\"DE\",\"weightFromKg\":0,\"weightToKg\":2,\"pricePerKg\":60,\"allowedMarks\":\"\",\"prohibitedMarks\":\"\"}]";
        var versionPayload = "{\"id\":\"" + versionId + "\",\"channelId\":\"" + channelId + "\",\"versionNumber\":1,\"status\":\"published\",\"sourceHash\":\"hash-1\",\"fileName\":\"rates.xlsx\",\"importedBy\":\"ADMIN\",\"publishedBy\":\"ADMIN\",\"rows\":" + rows + ",\"issues\":[],\"diffRows\":[],\"summary\":{\"added\":2}}";
        jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at,published_at) values(:id,:channel,1,'published','hash-1',cast(:payload as jsonb),:now,:now)")
                .param("id", versionId).param("channel", channelId).param("payload", versionPayload).param("now", now).update();
        jdbc.sql("update logistics_channel set current_version_id=:version where id=:id").param("version", versionId).param("id", channelId).update();
    }

    @Test
    void returnsPagedSummariesAndVersionArraysWithoutEmbeddingTheWorkspace() {
        var providers = service.providers(0, 50, "云途", true);
        var versions = service.versions(0, 50, channelId, "published");
        var rows = service.versionRows(versionId, 0, 1, "US", "");

        assertEquals(1, providers.total());
        assertEquals(2, versions.items().getFirst().path("rowCount").asInt());
        assertFalse(versions.items().getFirst().has("rows"));
        assertEquals(1, rows.items().size());
        assertEquals("US", rows.items().getFirst().path("countryCode").asText());
        assertThrows(AppException.class, () -> service.providers(-1, 50, "", null));
        assertThrows(AppException.class, () -> service.versions(0, 50, null, "invalid"));
    }

    @Test
    void filtersPublishedRulesAndChangesRevisionOnlyForPublishedMetadata() {
        var first = service.manifest();
        var rules = service.publishedRules(first.revision(), "普货", List.of("美国"), List.of("YT-PH"));
        assertEquals(1, rules.rules().size());
        assertEquals(1, rules.rules().getFirst().path("prices").size());
        assertTrue(first.countries().stream().anyMatch(country -> country.code().equals("US")));

        var draftId = UUID.randomUUID();
        jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at) values(:id,:channel,2,'draft','draft-hash','{\"rows\":[]}'::jsonb,now())")
                .param("id", draftId).param("channel", channelId).update();
        assertEquals(first.revision(), service.manifest().revision());

        jdbc.sql("update logistics_channel set payload=jsonb_set(payload,'{enabled}','false'::jsonb), version=version+1, updated_at=now() where id=:id").param("id", channelId).update();
        assertNotEquals(first.revision(), service.manifest().revision());
        assertThrows(AppException.class, () -> service.publishedRules(first.revision(), "普货", List.of("美国"), List.of()));
    }
}
