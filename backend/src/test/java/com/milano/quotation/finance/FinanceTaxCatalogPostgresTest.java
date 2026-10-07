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
        assertTrue(new tools.jackson.databind.ObjectMapper().valueToTree(controller.freightDiscountChannels().data()).isEmpty());
        jdbc.sql("""
            insert into logistics_version(id,channel_id,status,payload,source_hash,version_number,created_at,published_at)
            values('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002','published',
            '{"rows":[{"countryCode":"gb","areaName":"英国","weightFromKg":0,"weightToKg":2,"pricePerKg":100,"registrationFee":20,"pricingModel":"per-kg"}]}','test',1,now(),now())
            """).update();
        jdbc.sql("update logistics_channel set current_version_id='00000000-0000-0000-0000-000000000003',payload=jsonb_set(payload,'{enabled}','true')").update();
        jdbc.sql("""
            insert into logistics_billing_acceptance
            select gen_random_uuid(),id,rows_fingerprint,'legacy','legacy','{}','QA',now() from logistics_version
            """).update();
        var mapper=new tools.jackson.databind.ObjectMapper();
        var directory=mapper.valueToTree(controller.freightDiscountChannels().data());
        assertEquals(1,directory.size());assertEquals("00000000-0000-0000-0000-000000000002",directory.get(0).path("channelId").asText());
        assertEquals("GB",directory.get(0).path("countries").get(0).path("code").asText());
        var settings=mapper.createObjectNode();var rule=settings.putArray("rules").addObject().put("channelId","00000000-0000-0000-0000-000000000002")
            .put("enabled",true).put("basis","full-freight").put("defaultFactor",.9);rule.putObject("countries");
        assertDoesNotThrow(()->ChannelFreightDiscounts.validateBindings(jdbc,settings));
        rule.put("basis","base-excluding-linehaul");
        assertThrows(com.milano.quotation.common.AppException.class,()->ChannelFreightDiscounts.validateBindings(jdbc,settings));
        jdbc.sql("update logistics_channel set archived_at=now()").update();
        assertTrue(new tools.jackson.databind.ObjectMapper().valueToTree(controller.taxChannels().data()).isEmpty());
        assertTrue(mapper.valueToTree(controller.freightDiscountChannels().data()).isEmpty());
        rule.put("basis","full-freight");
        assertThrows(com.milano.quotation.common.AppException.class,()->ChannelFreightDiscounts.validateBindings(jdbc,settings));
        assertDoesNotThrow(()->ChannelFreightDiscounts.validateBindings(jdbc,settings,settings.deepCopy()));
        rule.put("enabled",false);assertDoesNotThrow(()->ChannelFreightDiscounts.validateBindings(jdbc,settings));
    }
}
