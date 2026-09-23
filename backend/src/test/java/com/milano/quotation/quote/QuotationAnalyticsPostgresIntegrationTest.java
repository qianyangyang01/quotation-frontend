package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class QuotationAnalyticsPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Test void snapshotRetainsAnalyticsFieldsLegacyCountriesAndLifecycleScope(){
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("create table quotation_record(id uuid primary key,payload jsonb,created_at timestamptz,lifecycle_state text,version bigint not null default 0,updated_at timestamptz not null default now())");
        jdbc.execute("""
            insert into quotation_record(id,payload,created_at,lifecycle_state) select md5(i::text)::uuid,
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
        // Returned JSON must not be able to poison the next reader's cached data.
        ((ObjectNode)result.items().getFirst()).put("systemQuoteUsd",999);
        assertTrue(controller.snapshot().data().items().stream().allMatch(row->row.path("systemQuoteUsd").asDouble()==12.35));
        jdbc.execute("update quotation_record set payload=jsonb_set(payload,'{systemQuoteUsd}','21.5'),version=version+1 where id=md5('1')::uuid");
        assertEquals(21.5,controller.snapshot().data().items().stream().filter(row->row.path("no").asText().equals("Q-1")).findFirst().orElseThrow().path("systemQuoteUsd").asDouble());
        jdbc.execute("update quotation_record set payload=jsonb_set(payload,'{systemQuoteUsd}','22.5'),updated_at=now() where id=md5('1')::uuid");
        assertEquals(22.5,controller.snapshot().data().items().stream().filter(row->row.path("no").asText().equals("Q-1")).findFirst().orElseThrow().path("systemQuoteUsd").asDouble());
        // A concurrent committed change cannot mix new amounts into an older snapshot.
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(status->{
            jdbc.queryForObject("select count(*) from quotation_record",Integer.class);
            try(var connection=ds.getConnection();var statement=connection.createStatement()){
                statement.executeUpdate("update quotation_record set payload=jsonb_set(payload,'{systemQuoteUsd}','23.5'),version=version+1 where id=md5('1')::uuid");
            }catch(java.sql.SQLException error){throw new RuntimeException(error);}
            assertEquals(22.5,controller.snapshot().data().items().stream().filter(row->row.path("no").asText().equals("Q-1")).findFirst().orElseThrow().path("systemQuoteUsd").asDouble());
        });
        assertEquals(23.5,controller.snapshot().data().items().stream().filter(row->row.path("no").asText().equals("Q-1")).findFirst().orElseThrow().path("systemQuoteUsd").asDouble());
        jdbc.execute("update quotation_record set lifecycle_state='trashed',version=version+1 where id=md5('1')::uuid");
        assertEquals(203,controller.snapshot().data().total());
        jdbc.execute("update quotation_record set lifecycle_state='archived',version=version+1 where id=md5('1')::uuid");
        assertEquals(204,controller.snapshot().data().total());
        jdbc.execute("delete from quotation_record");
        assertEquals(0,controller.snapshot().data().total());
    }
}
