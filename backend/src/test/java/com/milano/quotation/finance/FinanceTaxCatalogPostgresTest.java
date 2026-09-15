package com.milano.quotation.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class FinanceTaxCatalogPostgresTest {
    @Container static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Test void retainsDisabledChannelIdentityButExcludesArchivedData() {
        Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).locations("classpath:db/migration").load().migrate();
        var jdbc=JdbcClient.create(new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()));
        jdbc.sql("insert into logistics_provider(id,code,payload,version,created_at,updated_at) values('00000000-0000-0000-0000-000000000001','QA','{\"name\":\"云速递\"}',0,now(),now())").update();
        jdbc.sql("""
            insert into logistics_channel(id,provider_id,code,rule_id,payload,version,created_at,updated_at)
            values('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','C-disabled',901,'{"name":"全球专线带电","enabled":false}',0,now(),now())
            """).update();
        var controller=new FinanceSettingController(null,null,null,jdbc);
        var json=new tools.jackson.databind.ObjectMapper().valueToTree(controller.taxChannels().data());
        assertEquals(1,json.size());assertEquals(901,json.get(0).path("ruleId").asInt());
        assertEquals("云速递",json.get(0).path("carrier").asText());assertEquals("C-disabled",json.get(0).path("channelCode").asText());
        assertFalse(json.get(0).has("prices"));
        jdbc.sql("update logistics_channel set archived_at=now()").update();
        assertTrue(new tools.jackson.databind.ObjectMapper().valueToTree(controller.taxChannels().data()).isEmpty());
    }
}
