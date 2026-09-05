package com.milano.quotation.logistics;

import com.milano.quotation.common.AppException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
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
        var rows = "[{\"areaName\":\"美国\",\"countryCode\":\"US\",\"weightFromKg\":0,\"weightToKg\":2,\"pricePerKg\":55,\"registrationFee\":18,\"etaMinDays\":6,\"etaMaxDays\":10,\"allowedMarks\":\"\",\"prohibitedMarks\":\"\",\"pendingReason\":\"\",\"notes\":\"仅用于物流管理页的长说明\",\"rawValues\":{\"原始列\":\"原始值\"},\"sourceFile\":\"rates.xlsx\",\"sourceRow\":42,\"rowKey\":\"diagnostic-only\"},{\"areaName\":\"德国\",\"countryCode\":\"DE\",\"weightFromKg\":0,\"weightToKg\":2,\"pricePerKg\":60,\"allowedMarks\":\"\",\"prohibitedMarks\":\"\"}]";
        var versionPayload = "{\"id\":\"" + versionId + "\",\"channelId\":\"" + channelId + "\",\"versionNumber\":1,\"status\":\"published\",\"sourceHash\":\"hash-1\",\"fileName\":\"rates.xlsx\",\"importedBy\":\"ADMIN\",\"publishedBy\":\"ADMIN\",\"rows\":" + rows + ",\"issues\":[],\"diffRows\":[],\"summary\":{\"added\":2}}";
        jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at,published_at) values(:id,:channel,1,'published','hash-1',cast(:payload as jsonb),:now,:now)")
                .param("id", versionId).param("channel", channelId).param("payload", versionPayload).param("now", now).update();
        jdbc.sql("update logistics_channel set current_version_id=:version where id=:id").param("version", versionId).param("id", channelId).update();
        // This suite exercises pre-migration legacy query compatibility, not new-version approval.
        jdbc.sql("insert into logistics_billing_acceptance select gen_random_uuid(),id,md5((payload->'rows')::text),'legacy','legacy','{}','QA',now() from logistics_version where id=:id").param("id",versionId).update();
    }

    @BeforeEach
    void restorePublishedChannel() {
        jdbc.sql("update logistics_channel set archived_at=null, archived_by=null, archive_reason=null, payload=jsonb_set(payload,'{enabled}','true'::jsonb), version=version+1 where id=:id").param("id", channelId).update();
    }

    @Test
    void cachedPublishedRulesCannotSurviveChannelDisableUnderAnOldRevision() {
        var service = new LogisticsQueryService(jdbc, new ObjectMapper());
        var original = jdbc.sql("select payload::text from logistics_channel where id=:id").param("id",channelId).query(String.class).single();
        var revision = service.manifestRevision().revision();
        var first = service.publishedRules(revision, "普货", List.of("US"), List.of());
        assertEquals(1, first.rules().size());
        org.junit.jupiter.api.Assertions.assertSame(first, service.publishedRules(revision, "普货", List.of("US"), List.of()));
        try {
            jdbc.sql("update logistics_channel set payload=jsonb_set(payload,'{enabled}','false'::jsonb) where id=:id").param("id",channelId).update();
            org.junit.jupiter.api.Assertions.assertThrows(AppException.class, () -> service.publishedRules(revision, "普货", List.of("US"), List.of()));
            assertTrue(service.publishedRules("", "普货", List.of("US"), List.of()).rules().isEmpty());
        } finally {
            jdbc.sql("update logistics_channel set payload=cast(:payload as jsonb) where id=:id").param("payload",original).param("id",channelId).update();
        }
    }

    @Test
    void acceptanceEligibilityChangesInvalidateCachedCountEvenWithoutANewReviewTime() {
        var service = new LogisticsQueryService(jdbc, new ObjectMapper());
        var before = service.manifest();
        assertEquals(1, before.publishedChannels());
        try {
            jdbc.sql("update logistics_billing_acceptance set kind='verified',engine_version='unsupported-engine' where version_id=:id").param("id",versionId).update();
            var after = service.manifest();
            assertNotEquals(before.revision(), after.revision());
            assertEquals(0, after.publishedChannels());
            assertThrows(AppException.class, () -> service.publishedRules(before.revision(), "普货", List.of("US"), List.of()));
        } finally {
            jdbc.sql("update logistics_billing_acceptance set kind='legacy',engine_version='legacy' where version_id=:id").param("id",versionId).update();
        }
        assertEquals(1, service.manifest().publishedChannels());
    }

    @Test
    void compactReadProjectionPreservesEligibilityAndTracksSourceEdits() {
        var original = jdbc.sql("select payload::text from logistics_version where id=:id").param("id",versionId).query(String.class).single();
        try {
            var mapper = new ObjectMapper();
            var source = (tools.jackson.databind.node.ObjectNode) mapper.readTree(original);
            source.put("sourceEvidence", "large source explanation ".repeat(30000));
            var first = (tools.jackson.databind.node.ObjectNode) source.path("rows").get(0);
            first.put("pendingReason", "暂停收货");
            first.put("pricePerKg", 77);
            source.withArray("rows").addObject().put("areaName","美国").put("countryCode","US").put("pricingModel","first-next").put("firstWeightPrice",10);
            jdbc.sql("update logistics_version set payload=cast(:payload as jsonb) where id=:id").param("payload",source.toString()).param("id",versionId).update();
            var projected = mapper.readTree(jdbc.sql("select quote_rows::text from logistics_version where id=:id").param("id",versionId).query(String.class).single());
            assertEquals(2,projected.size());
            assertEquals(77,projected.get(0).path("pricePerKg").asInt());
            assertEquals("暂停收货",projected.get(0).path("pendingReason").asText());
            assertFalse(LogisticsBillingEngine.available(projected.get(0)));
            assertFalse(projected.get(0).has("rawValues"));
            assertFalse(projected.get(0).has("notes"));
            assertTrue(jdbc.sql("select rows_fingerprint=md5((payload->'rows')::text) and length(quote_rows::text)<length(payload::text)/100 from logistics_version where id=:id").param("id",versionId).query(Boolean.class).single());
            assertFalse(jdbc.sql("select logistics_version_quote_ready(:id)").param("id",versionId).query(Boolean.class).single());
            assertEquals(source,jdbc.sql("select payload::text from logistics_version where id=:id").param("id",versionId).query((rs,n)->mapper.readTree(rs.getString(1))).single());
        } finally {
            jdbc.sql("update logistics_version set payload=cast(:payload as jsonb) where id=:id").param("payload",original).param("id",versionId).update();
        }
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
        jdbc.sql("update logistics_channel set archived_at=null, archived_by=null, archive_reason=null, payload=jsonb_set(payload,'{enabled}','true'::jsonb), version=version+1 where id=:id").param("id", channelId).update();
        var first = service.manifest();
        var rules = service.publishedRules(first.revision(), "普货", List.of("美国"), List.of("YT-PH"));
        assertEquals(1, rules.rules().size());
        assertEquals(1, rules.rules().getFirst().path("prices").size());
        assertTrue(first.countries().stream().anyMatch(country -> country.code().equals("US")));
        assertEquals("US", service.publishedRules(first.revision(), "普货", List.of("us"), List.of("yt-ph"))
                .rules().getFirst().path("prices").get(0).path("countryCode").asText());
        assertEquals("US", service.publishedRules(first.revision(), "普货", List.of("uS"), List.of("YT-PH"))
                .rules().getFirst().path("prices").get(0).path("countryCode").asText());
        assertTrue(service.publishedRules(first.revision(), "普货", List.of("美国"), List.of("missing-channel")).rules().isEmpty());

        var price = rules.rules().getFirst().path("prices").get(0);
        assertEquals(55, price.path("pricePerKg").asInt());
        assertEquals(18, price.path("registrationFee").asInt());
        assertEquals(6, price.path("etaMinDays").asInt());
        assertEquals(10, price.path("etaMaxDays").asInt());
        assertTrue(price.path("quoteReady").asBoolean());
        assertFalse(price.has("notes"));
        assertFalse(price.has("rawValues"));
        assertFalse(price.has("sourceFile"));
        assertFalse(price.has("sourceRow"));
        assertFalse(price.has("rowKey"));
        assertFalse(price.has("pendingReason"));

        var draftId = UUID.randomUUID();
        jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at) values(:id,:channel,2,'draft','draft-hash','{\"rows\":[]}'::jsonb,now())")
                .param("id", draftId).param("channel", channelId).update();
        assertEquals(first.revision(), service.manifest().revision());

        jdbc.sql("update logistics_channel set payload=jsonb_set(payload,'{enabled}','false'::jsonb), version=version+1, updated_at=now() where id=:id").param("id", channelId).update();
        assertNotEquals(first.revision(), service.manifest().revision());
        assertThrows(AppException.class, () -> service.publishedRules(first.revision(), "普货", List.of("美国"), List.of()));
    }

    @Test
    void filtersTheMaximumCountryBatchAsASetInsteadOfExpandingOrConditions() {
        jdbc.sql("update logistics_channel set archived_at=null, archived_by=null, archive_reason=null, payload=jsonb_set(payload,'{enabled}','true'::jsonb), version=version+1 where id=:id").param("id", channelId).update();
        var revision = service.manifest().revision();
        var countries = new ArrayList<String>();
        countries.add("US");
        for (int index = 1; index < 100; index++) countries.add("COUNTRY-" + index);

        var rules = assertTimeout(Duration.ofSeconds(5), () -> service.publishedRules(revision, "普货", countries, List.of()));

        assertEquals(1, rules.rules().size());
        assertEquals("US", rules.rules().getFirst().path("prices").get(0).path("countryCode").asText());
        countries.add("COUNTRY-100");
        assertThrows(AppException.class, () -> service.publishedRules(revision, "普货", countries, List.of()));
    }

    @Test
    void returnsOneLightweightFinanceCatalogWithCountryCoverage() {
        var revision = service.manifest().revision();
        var catalog = assertTimeout(Duration.ofSeconds(5), () -> service.publishedCatalog(revision));

        assertEquals(1, catalog.rules().size());
        assertEquals(2, catalog.rules().getFirst().path("prices").size());
        assertTrue(catalog.rules().getFirst().path("prices").toString().contains("US"));
        assertTrue(catalog.rules().getFirst().path("prices").toString().contains("DE"));
        assertFalse(catalog.rules().getFirst().has("logisticsVersionId"));
        assertFalse(catalog.rules().getFirst().path("prices").get(0).has("weightFromKg"));
    }

    @Test
    void archivedChannelLeavesDailyListManifestAndPublishedQuoteProjection() {
        jdbc.sql("update logistics_channel set archived_at=null, archived_by=null, archive_reason=null, payload=jsonb_set(payload,'{enabled}','true'::jsonb), version=version+1 where id=:id").param("id", channelId).update();
        assertEquals(1, service.channels(0, 50, "", providerId, null, false).total());
        jdbc.sql("update logistics_channel set archived_at=now(), archived_by='ADMIN', archive_reason='下线测试', version=version+1 where id=:id").param("id", channelId).update();
        assertEquals(0, service.channels(0, 50, "", providerId, null, false).total());
        var archived = service.channels(0, 50, "", providerId, null, true);
        assertEquals(1, archived.total());
        assertTrue(archived.items().getFirst().path("archived").asBoolean());
        assertEquals("下线测试", archived.items().getFirst().path("archiveReason").asText());
        assertEquals(0, service.manifest().publishedChannels());
        assertTrue(service.manifest().countries().isEmpty());
    }
}
