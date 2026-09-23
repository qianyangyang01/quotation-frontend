package com.milano.quotation.quote;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class QuotationMetadataSearchPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Test void optimizedFlagsAndCountriesMatchOriginalQueriesAcrossRolesFiltersAndUpdates(){
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var jdbc=new NamedParameterJdbcTemplate(ds);var sql=jdbc.getJdbcTemplate();var mapper=new ObjectMapper();
        sql.execute("create table quotation_record(id uuid primary key,quote_no text,owner_account text,status text,lifecycle_state text,version bigint,created_at timestamptz,updated_at timestamptz,payload jsonb)");
        sql.execute("create table quotation_review(id uuid primary key,status text,claimant_account text,state jsonb,version bigint)");
        sql.execute("""
            insert into quotation_record select md5(n::text)::uuid,'Q-'||n,case when n%2=0 then 'A' else 'B' end,
              case when n%3=0 then 'won' when n%3=1 then 'pending' else 'lost' end,
              case when n%10=0 then 'trashed' when n%10=1 then 'archived' else 'active' end,0,'2026-09-24T01:00:00Z',now(),
              jsonb_build_object('no','Q-'||n,'primarySku','SKU-'||n,'customerName','客户100%','productCategory','服装',
                'country','美国','quoteConfirmed',case when n%5=0 then 'true'::jsonb when n%5=1 then '"true"'::jsonb else null end,
                'financeReviewStatus',case when n%4=0 then 'approved' else 'pending' end,
                'quoteOptions',jsonb_build_array(jsonb_build_object('country','法国','channel','渠道A')))
            from generate_series(1,80)n
            """);
        sql.execute("insert into quotation_review select id,case when quote_no in ('Q-2','Q-3') then 'reviewing' else 'approved' end,'F1',jsonb_build_object('financeReviewStatus',case when quote_no in ('Q-2','Q-3') then 'reviewing' else 'approved' end,'financeReviewClaimedAccount','F1'),1 from quotation_record where quote_no in ('Q-2','Q-3','Q-5')");
        var original=new QuotationRecordQuery(jdbc,mapper);var optimized=new QuotationRecordQuery(jdbc,mapper);
        ReflectionTestUtils.setField(optimized,"countryIndex",new QuotationCountryIndex(JdbcClient.create(ds),mapper));
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setReadOnly(true);
        for(int round=0;round<2;round++){
            for(var owner:Arrays.asList(null,"A","B","empty"))for(var lifecycle:List.of("active","archived","trashed")){
                var filters=new ArrayList<QuotationRecordQuery.Filters>();
                for(var status:List.of("","pending","processed","won","lost","finance-pending","finance-mine"))filters.add(new QuotationRecordQuery.Filters("",status,"","",null,null,lifecycle,"F1"));
                for(var review:List.of("pending","approved","rejected","reviewing"))filters.add(new QuotationRecordQuery.Filters("","","","",null,null,lifecycle,"F1",review,false));
                filters.add(new QuotationRecordQuery.Filters("100%","pending","法国","服装",LocalDate.parse("2026-09-24"),LocalDate.parse("2026-09-24"),lifecycle,"F1","reviewing",true));
                for(var f:filters)tx.execute(ignored->{assertEquals(original.search(owner,f,1,10),optimized.search(owner,f,1,10),owner+" "+f);return null;});
            }
            sql.execute("update quotation_record set payload=payload||'{\"quoteConfirmed\":true,\"country\":\"Germany\"}',version=version+1,updated_at=now(),owner_account='B',lifecycle_state='active' where quote_no in ('Q-12','Q-13')");
            sql.execute("delete from quotation_record where quote_no='Q-7'");
        }
    }
}
