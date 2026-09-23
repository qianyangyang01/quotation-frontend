package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class QuotationRecordQueryPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Test void filtersInDatabaseWithInclusiveShanghaiDaysStablePagesAndOwnerScope() {
        var jdbc=new NamedParameterJdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        jdbc.getJdbcTemplate().execute("create table quotation_record(id uuid primary key,quote_no text,owner_account text,status text,payload jsonb,version bigint,created_at timestamptz)");
        jdbc.getJdbcTemplate().execute("insert into quotation_record select md5(i::text)::uuid,'Q-'||i,'ME','pending',jsonb_build_object('id',md5(i::text),'no','Q-'||i,'customerName','客户100%','productCategory','服装','country','美国','quoteOptions',jsonb_build_array(jsonb_build_object('country','法国','channel','渠道A'))),0,'2026-09-09 16:00:00+00'::timestamptz from generate_series(1,105) i");
        jdbc.getJdbcTemplate().execute("insert into quotation_record values(md5('other')::uuid,'OTHER','OTHER','won','{\"country\":\"日本\"}',0,'2026-09-10 15:59:59.999999+00'),(md5('next')::uuid,'NEXT','ME','lost','{}',0,'2026-09-10 16:00:00+00'),(md5('before')::uuid,'BEFORE','ME','lost','{}',0,'2026-09-09 15:59:59.999999+00')");
        var query=new QuotationRecordQuery(jdbc,new ObjectMapper());var date=LocalDate.parse("2026-09-10");
        var filters=new QuotationRecordQuery.Filters("","","","",date,date);
        var mine=query.search("ME",filters,0,10);assertEquals(105,mine.total());assertEquals(11,mine.totalPages());assertEquals(105,mine.summary().pending());assertEquals(10,mine.items().size());assertFalse(mine.countries().contains("日本"));assertTrue(mine.countries().contains("法国"));
        var second=query.search("ME",filters,1,10);assertTrue(second.items().stream().noneMatch(mine.items()::contains));
        assertEquals(5,query.search("ME",filters,10,10).items().size());assertEquals(10,query.search("ME",filters,999,10).page());
        assertEquals(106,query.search(null,filters,0,10).total());assertEquals(1,query.search(null,filters,0,10).summary().won());
        assertEquals(105,query.search("ME",new QuotationRecordQuery.Filters("100%","pending","法国","服装",date,date),0,30).total());
        assertEquals(0,query.search("ME",new QuotationRecordQuery.Filters("100_","","","",date,date),0,50).total());
        assertEquals(0,query.search("ME",new QuotationRecordQuery.Filters("","won","","",date,date),0,50).total());
        assertThrows(RuntimeException.class,()->query.search("ME",new QuotationRecordQuery.Filters("","","","",date.plusDays(1),date),0,10));
        assertEquals(107,query.search("ME",new QuotationRecordQuery.Filters("","","","",null,null),0,100).total());
        jdbc.getJdbcTemplate().execute("update quotation_record set payload=payload||'{\"quoteConfirmed\":true}'::jsonb where quote_no in ('Q-1','Q-2','OTHER')");
        var confirmed=query.search("ME",new QuotationRecordQuery.Filters("","processed","","",date,date),0,10);
        assertEquals(2,confirmed.total());assertEquals(2,confirmed.summary().processed());assertEquals(0,confirmed.summary().won());
        assertEquals(103,query.search("ME",new QuotationRecordQuery.Filters("","pending","","",date,date),0,10).total());
        var all=query.search(null,filters,0,10);
        assertEquals(1,all.summary().won());assertEquals(106,all.total());
        assertEquals(all.total(),all.summary().pending()+all.summary().processed()+all.summary().won()+all.summary().lost());
        jdbc.getJdbcTemplate().execute("update quotation_record set payload=payload||'{\"financeReviewStatus\":\"approved\"}'::jsonb where quote_no in ('Q-1','OTHER')");
        jdbc.getJdbcTemplate().execute("update quotation_record set payload=payload||'{\"financeReviewStatus\":\"rejected\"}'::jsonb where quote_no='Q-2'");
        assertEquals(1,query.search("ME",new QuotationRecordQuery.Filters("","finance-approved","","",date,date),0,10).total());
        assertEquals(2,query.search(null,new QuotationRecordQuery.Filters("","finance-approved","","",date,date),0,10).total());
        assertEquals(1,query.search("ME",new QuotationRecordQuery.Filters("","finance-rejected","","",date,date),0,10).total());
        jdbc.getJdbcTemplate().execute("update quotation_record set payload=payload||'{\"financeReviewStatus\":\"pending\"}'::jsonb where quote_no='Q-3'");
        jdbc.getJdbcTemplate().execute("update quotation_record set payload=payload||'{\"financeReviewStatus\":null}'::jsonb where quote_no='Q-4'");
        jdbc.getJdbcTemplate().execute("update quotation_record set payload=payload||'{\"financeReviewStatus\":\"\"}'::jsonb where quote_no='Q-5'");
        var pendingReviewFilters=new QuotationRecordQuery.Filters("100%","finance-pending","法国","服装",date,date);
        var pendingReview=query.search("ME",pendingReviewFilters,0,100);
        assertEquals(103,pendingReview.total());assertEquals(103,pendingReview.summary().pending());
        var exportRows=new java.util.ArrayList<>(pendingReview.items());
        exportRows.addAll(query.search("ME",pendingReviewFilters,1,100).items());
        assertEquals(103,exportRows.size());assertEquals(103,exportRows.stream().map(row->row.path("id").asText()).distinct().count());
        assertTrue(exportRows.stream().noneMatch(row->java.util.Set.of("approved","rejected").contains(row.path("financeReviewStatus").asText())));
        for(var no:java.util.List.of("Q-3","Q-4","Q-5","Q-6")) assertTrue(exportRows.stream().anyMatch(row->row.path("no").asText().equals(no)));
        jdbc.getJdbcTemplate().execute("update quotation_record set payload=payload||'{\"financeReviewStatus\":\"pending\"}'::jsonb where quote_no='OTHER'");
        var allPendingReview=new QuotationRecordQuery.Filters("","finance-pending","","",date,date);
        assertEquals(103,query.search("ME",allPendingReview,0,10).total());
        assertEquals(104,query.search(null,allPendingReview,0,10).total());
        assertEquals(1,query.search(null,allPendingReview,0,10).summary().won()); // Review and deal status are independent.
        jdbc.getJdbcTemplate().execute("update quotation_record set payload=payload||'{\"financeReviewStatus\":\"approved\"}'::jsonb where quote_no='OTHER'");
        var everyDate=new QuotationRecordQuery.Filters("","","","",null,null);
        var historical=query.search("ME",everyDate,0,100);
        assertEquals(105,historical.summary().pending()); // 103 pending + 2 legacy lost
        assertEquals(historical.total(),historical.summary().pending()+historical.summary().processed()+historical.summary().won());
        assertEquals(105,query.search("ME",new QuotationRecordQuery.Filters("","pending","","",null,null),0,100).total());
        jdbc.getJdbcTemplate().execute("update quotation_record set payload=payload||'{\"quoteConfirmed\":true}'::jsonb where quote_no='NEXT'");
        assertEquals(3,query.search("ME",new QuotationRecordQuery.Filters("","processed","","",null,null),0,100).total());
    }
}
