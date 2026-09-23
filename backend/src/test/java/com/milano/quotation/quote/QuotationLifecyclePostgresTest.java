package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class QuotationLifecyclePostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Test void migrationPreservesPayloadAndQueriesSeparateLifecycleIncludingCountryAndExportPages() throws Exception {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var jdbc=new NamedParameterJdbcTemplate(ds);var sql=jdbc.getJdbcTemplate();
        sql.execute("create table quotation_record(id uuid primary key,quote_no text,owner_account text,status text,payload jsonb,version bigint,created_at timestamptz)");
        sql.execute("insert into quotation_record select md5(i::text)::uuid,'Q-'||i,'ME','pending',jsonb_build_object('id',md5(i::text)::uuid::text,'no','Q-'||i,'country',case when i=3 then '仅回收站国家' else '美国' end,'customerName','测试客户'),0,now() from generate_series(1,5) i");
        var before=sql.queryForList("select payload::text from quotation_record order by id",String.class);
        try(var connection=ds.getConnection()) { ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V50__quotation_review_claim.sql")); ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V51__quotation_record_lifecycle.sql")); }
        assertEquals(before,sql.queryForList("select payload::text from quotation_record order by id",String.class));
        assertEquals(5,sql.queryForObject("select count(*) from quotation_record where lifecycle_state='active'",Integer.class));
        sql.execute("update quotation_record set lifecycle_state=case when quote_no='Q-2' then 'archived' else 'trashed' end where quote_no in ('Q-2','Q-3')");
        var query=new QuotationRecordQuery(jdbc,new ObjectMapper());
        var active=new QuotationRecordQuery.Filters("测试","","","",null,null,"active");
        var page=query.search("ME",active,0,2);assertEquals(3,page.total());assertEquals(3,page.summary().pending());assertFalse(page.countries().contains("仅回收站国家"));
        assertEquals(1,query.search("ME",active,1,2).items().size());
        for(var state:java.util.List.of("archived","trashed")) {
            var result=query.search("ME",new QuotationRecordQuery.Filters("","","","",null,null,state),0,10);
            assertEquals(1,result.total());assertEquals(state,result.items().getFirst().path("lifecycleState").asText());
        }
        assertEquals(0,query.search("OTHER",active,0,10).total());
        assertThrows(RuntimeException.class,()->query.search("ME",new QuotationRecordQuery.Filters("","","","",null,null,"all"),0,10));
        assertThrows(Exception.class,()->sql.execute("update quotation_record set lifecycle_state='deleted'"));
    }
}
