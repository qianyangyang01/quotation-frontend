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
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class LogisticsDraftReviewPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    static final ObjectMapper mapper=new ObjectMapper();static JdbcClient jdbc;static LogisticsDatasetGuard guard;static LogisticsDraftReviewService review;

    @BeforeAll static void setup() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration").load().migrate();
        var dataSource=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());jdbc=JdbcClient.create(dataSource);guard=new LogisticsDatasetGuard(jdbc);
        review=new LogisticsDraftReviewService(jdbc,mapper,new LogisticsWorkbookService(mapper),guard);
    }

    @Test void updatesEveryWeightTierForOneRouteAuditsItAndRejectsAStaleFingerprint() {
        var dataset=guard.activeId();var provider=UUID.randomUUID();var channel=UUID.randomUUID();var version=UUID.randomUUID();
        jdbc.sql("insert into logistics_provider(id,dataset_id,code,payload,created_at,updated_at) values(:id,:d,:code,'{\"name\":\"测试商\",\"enabled\":true}',now(),now())").param("id",provider).param("d",dataset).param("code",provider.toString()).update();
        jdbc.sql("insert into logistics_channel(id,dataset_id,provider_id,rule_id,code,payload,created_at,updated_at) values(:id,:d,:p,:rule,:code,'{\"name\":\"测试渠道\",\"enabled\":true}',now(),now())").param("id",channel).param("d",dataset).param("p",provider).param("rule",guard.nextRuleId()).param("code",channel.toString()).update();
        var payload=mapper.createObjectNode().put("id",version.toString()).put("channelId",channel.toString()).put("templateStatus","known").put("basePublishedVersionId","").put("errors",0).put("batchId","");payload.putArray("issues");
        var rows=payload.putArray("rows");rows.add(row("tier-1",0,1,2));rows.add(row("tier-2",1,2,3));
        jdbc.sql("insert into logistics_version(id,channel_id,version_number,status,source_hash,payload,created_at) values(:id,:c,1,'draft','eta-test',cast(:p as jsonb),now())").param("id",version).param("c",channel).param("p",payload.toString()).update();

        var before=review.load(version,false);assertFalse(before.path("etaReady").asBoolean());var routeKey=before.path("missingEtaRoutes").get(0).path("routeKey").asText();
        var input=mapper.createObjectNode().put("fingerprint",before.path("fingerprint").asText());input.putArray("changes");input.putArray("etaChanges").addObject().put("routeKey",routeKey).put("etaMinDays",7).put("etaMaxDays",15);
        var updated=review.patch(version,input,"ETA-REVIEWER");

        assertTrue(updated.path("etaReady").asBoolean());assertTrue(updated.path("missingEtaRoutes").isEmpty());assertTrue(updated.path("pricingReady").asBoolean());
        for(var price:updated.path("rows")){assertEquals(7,price.path("etaMinDays").asInt());assertEquals(15,price.path("etaMaxDays").asInt());assertEquals("manual-review",price.path("etaSource").asText());}
        var history=updated.path("correctionHistory").get(0);assertEquals("ETA-REVIEWER",history.path("editedBy").asText());assertEquals(2,history.path("etaChanges").get(0).path("affectedRows").asInt());
        assertThrows(AppException.class,()->review.patch(version,input,"STALE"));
    }

    private static ObjectNode row(String key,double from,double to,int sourceRow) {
        return mapper.createObjectNode().put("rowKey",key).put("areaName","美国").put("countryCode","US").put("zoneName","1区").put("originRegion","").put("sourceOriginRegion","华东")
                .put("sourceProductCode","TEST-US").put("pricingModel","per-kg").put("pricePerKg",50).put("registrationFee",20).put("currency","CNY")
                .put("weightFromKg",from).put("weightToKg",to).put("weightFromInclusive",from==0).put("weightToInclusive",true).put("sourceSheet","价格").put("sourceRow",sourceRow);
    }
}
