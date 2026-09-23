package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class QuotationAnalyticsPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Test void snapshotRetainsAnalyticsFieldsLegacyCountriesAndLifecycleScope(){
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("create table quotation_record(id uuid primary key,payload jsonb,created_at timestamptz,lifecycle_state text)");
        jdbc.execute("""
            insert into quotation_record select md5(i::text)::uuid,
              jsonb_build_object('no','Q-'||i,'primarySku','S-'||i,'customerName','客户','status','won',
                'createdAt','2026-09-23T01:02:03.123456789Z','updatedAt','2026-09-23T01:02:03Z',
                'systemQuoteUsd',12.35,'systemQuoteCny',86.45,'totalCostCny',40,
                'quoteOptions',jsonb_build_array(jsonb_build_object('country','美国','logisticsCalculation',repeat('x',10000))),
                'weightSnapshot',jsonb_build_object('private','not in summary')),
              now(),case when i=205 then 'trashed' when i=204 then 'archived' else 'active' end
            from generate_series(1,205)i
            """);
        jdbc.execute("update quotation_record set payload=(payload-'quoteOptions')||jsonb_build_object('specifiedQuotes',jsonb_build_array(jsonb_build_object('country','英国'))) where id=md5('1')::uuid");
        var controller=new QuotationAnalyticsController(JdbcClient.create(ds),new ObjectMapper());
        var result=controller.snapshot().data();
        assertEquals(204,result.total());assertEquals(204,result.items().size());
        assertTrue(result.items().stream().anyMatch(row->row.path("quoteOptions").get(0).path("country").asText().equals("英国")));
        for(var row:result.items()) {
            assertEquals("2026-09-23T01:02:03.123456789Z",row.path("createdAt").asText());
            assertEquals(12.35,row.path("systemQuoteUsd").asDouble());
            assertFalse(row.has("weightSnapshot"));
            assertEquals(1,row.path("quoteOptions").get(0).size());
        }
        jdbc.execute("delete from quotation_record");
        assertEquals(0,controller.snapshot().data().total());
    }
}
