package com.milano.quotation.quote;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class QuotationProductSearchPostgresTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    JdbcTemplate jdbc;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        jdbc.execute("drop schema public cascade");
        jdbc.execute("create schema public");
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("61").load().migrate();
    }
    void migrate() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();
    }
    @Test void migrationPreservesEverySavedFieldAndReviewAndMaintainsDerivedTextOnWrites() {
        jdbc.execute("""
            insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at)
            values ('00000000-0000-0000-0000-000000000001','QT-OLD','ME','won',
              '{"primarySku":"SKU-OLD","systemQuoteUsd":19.21,"customerQuote":{"prices":[18.99]},
                "logisticsFee":3.15,"financeReviewStatus":"approved","revisions":[{"before":18,"after":18.99}],
                "quotationBatch":{"no":"BQT-OLD"},"bundleItems":[{"sku":"BUNDLE-OLD","name":"组合商品"}]}',
              9,'2026-09-01T00:00:00Z','2026-09-02T00:00:00Z')
            """);
        jdbc.execute("""
            insert into quotation_review(id,status,state,version)
            values ('00000000-0000-0000-0000-000000000001','approved',
              '{"financeReviewedBy":"Finance","financeReviewedAt":"2026-09-02T00:00:00Z",
                "financeReviewStatus":"approved","history":[{"action":"complete","after":"approved"}]}',7)
            """);
        var before=jdbc.queryForObject("select to_jsonb(q)::text from quotation_record q",String.class);
        var review=jdbc.queryForObject("select to_jsonb(r)::text from quotation_review r",String.class);
        migrate();
        assertEquals(before,jdbc.queryForObject("select (to_jsonb(q)-'product_search_text')::text from quotation_record q",String.class));
        assertEquals(review,jdbc.queryForObject("select to_jsonb(r)::text from quotation_review r",String.class));
        assertTrue(jdbc.queryForObject("select product_search_text from quotation_record",String.class).contains("bqt-old sku-old"));
        jdbc.execute("update quotation_record set payload=jsonb_set(payload,'{primarySku}','\"SKU-NEW\"'::jsonb),quote_no='QT-NEW'");
        var text=jdbc.queryForObject("select product_search_text from quotation_record",String.class);
        assertTrue(text.contains("qt-new"));assertTrue(text.contains("sku-new"));assertFalse(text.contains("sku-old"));
        assertEquals(review,jdbc.queryForObject("select to_jsonb(r)::text from quotation_review r",String.class));
    }
    @Test void indexedSearchAvoidsParsingLargeSnapshotsAndRetainsTheSameMatches() {
        jdbc.execute("""
            insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at)
            select md5(n::text)::uuid,'QT-'||n,'ME','pending',
              jsonb_build_object('primarySku','SKU-'||n,'salespersonName','业务员',
                'bundleItems',jsonb_build_array(jsonb_build_object('sku','BUNDLE-'||n)),
                'quoteOptions',(select jsonb_agg(jsonb_build_object('id',k,'channel',md5((n*100+k)::text),
                  'snapshot',repeat(md5((n*200+k)::text),8))) from generate_series(1,60) k)),
              0,now(),now() from generate_series(1,3100) n
            """);
        migrate();jdbc.execute("analyze quotation_record");
        var oldSql="""
            select count(*) from quotation_record where lifecycle_state='active' and
              position('sku-3099' in lower(concat_ws(' ',quote_no,payload->'quotationBatch'->>'no',
                payload->>'primarySku',payload->>'productSummary',payload->>'salespersonName',
                (select string_agg(concat_ws(' ',b->>'sku',b->>'name'),' ') from jsonb_array_elements(
                  case when jsonb_typeof(payload->'bundleItems')='array' then payload->'bundleItems' else '[]'::jsonb end) b))))>0
            """;
        var indexedSql="select count(*) from quotation_record where lifecycle_state='active' and product_search_text like '%sku-3099%'";
        assertEquals(jdbc.queryForObject(oldSql,Long.class),jdbc.queryForObject(indexedSql,Long.class));
        var oldPlan=String.join("\n",jdbc.queryForList("explain (analyze,buffers,timing off) "+oldSql,String.class));
        var newPlan=String.join("\n",jdbc.queryForList("explain (analyze,buffers,timing off) "+indexedSql,String.class));
        assertTrue(newPlan.contains("quotation_record_product_search_idx"),newPlan);
        assertFalse(newPlan.contains("Function Scan"),newPlan);
        System.out.println("PRODUCT_SEARCH_BEFORE\n"+oldPlan+"\nPRODUCT_SEARCH_AFTER\n"+newPlan);
    }

    @Test void shippingNoteSearchUsesExistingTrigramIndexWithoutReadingEveryPricingSnapshot() {
        migrate();
        jdbc.execute("""
            insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at)
            select md5(n::text)::uuid,'QT-'||n,'ME','pending',
              jsonb_build_object('quoteMode','shipping-only','primarySku','',
                'shippingNote','TC2600009-'||n||' 客户自备蓝色',
                'productSummary','仅代发货报价：TC2600009-'||n||' 客户自备蓝色',
                'quoteOptions',(select jsonb_agg(jsonb_build_object('id',k,'channel',md5((n*100+k)::text),
                  'snapshot',repeat(md5((n*200+k)::text),8))) from generate_series(1,60) k)),
              0,now(),now() from generate_series(1,3100) n
            """);
        // Flush the GIN pending list after this artificial bulk load, as normal autovacuum does.
        jdbc.execute("vacuum analyze quotation_record");
        var sql = "select count(*) from quotation_record where lifecycle_state='active' and product_search_text like '%tc2600009-3099%'";
        assertEquals(1L, jdbc.queryForObject(sql, Long.class));
        var plan = String.join("\n", jdbc.queryForList("explain (analyze,buffers,timing off) " + sql, String.class));
        assertTrue(plan.contains("quotation_record_product_search_idx"), plan);
        assertFalse(plan.contains("Function Scan"), plan);
        assertFalse(plan.contains("Seq Scan on quotation_record"), plan);
        System.out.println("SHIPPING_NOTE_INDEXED_SEARCH\n" + plan);
    }
}
