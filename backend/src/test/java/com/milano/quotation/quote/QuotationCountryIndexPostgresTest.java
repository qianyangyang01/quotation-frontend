package com.milano.quotation.quote;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class QuotationCountryIndexPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Test void countriesKeepExactDatabaseOrderAndFreshOwnerLifecycleScope(){
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var jdbc=new JdbcTemplate(ds);var index=new QuotationCountryIndex(JdbcClient.create(ds),new ObjectMapper());
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setReadOnly(true);
        jdbc.execute("create table quotation_record(id uuid primary key,owner_account text,lifecycle_state text,version bigint,updated_at timestamptz,payload jsonb)");
        jdbc.execute("insert into quotation_record select md5(n::text)::uuid,case when n%2=0 then 'A' else 'B' end,case when n%3=0 then 'trashed' else 'active' end,0,now(),jsonb_build_object('country','英国','quoteOptions',jsonb_build_array(jsonb_build_object('country','美国'),jsonb_build_object('country','France'),jsonb_build_object('country','—'))) from generate_series(1,601)n");
        for(int round=0;round<3;round++){
            for(var owner:Arrays.asList(null,"A","B","nobody"))for(var lifecycle:List.of("active","trashed","archived"))tx.execute(status->{
                var scope=" where lifecycle_state=?"+(owner==null?"":" and owner_account=?");
                Object[] params=owner==null?new Object[]{lifecycle,lifecycle}:new Object[]{lifecycle,owner,lifecycle,owner};
                var expected=jdbc.queryForList("select distinct country from (select payload->>'country' country from quotation_record"+scope+" union select o->>'country' country from quotation_record cross join lateral jsonb_array_elements(case when jsonb_typeof(payload->'quoteOptions')='array' then payload->'quoteOptions' else '[]'::jsonb end) o"+scope+") c where country is not null and country<>'' and country<>'—' order by country",String.class,params);
                assertEquals(expected,index.countries(owner,lifecycle));return null;
            });
            jdbc.execute("update quotation_record set payload='{\"country\":\"德国\",\"quoteOptions\":{\"country\":\"must-not-match\"}}',version=version+1,updated_at=now(),owner_account='A',lifecycle_state='archived' where id=md5('1')::uuid");
            jdbc.execute("delete from quotation_record where id=md5('2')::uuid");
        }
        jdbc.execute("delete from quotation_record");assertTrue(index.countries(null,"active").isEmpty());
    }
}
