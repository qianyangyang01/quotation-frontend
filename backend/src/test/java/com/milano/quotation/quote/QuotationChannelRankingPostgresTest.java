package com.milano.quotation.quote;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class QuotationChannelRankingPostgresTest {
 @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
 @Test void countsSnapshotsOnceFiltersAndNeverWritesHistory() throws Exception {
  var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
  var jdbc=new JdbcTemplate(ds);
  jdbc.execute("""
   create table quotation_record(id uuid primary key,payload jsonb,created_at timestamptz,lifecycle_state text);
   create index quote_dates on quotation_record(lifecycle_state,created_at);
   create table purchase_product(sku text primary key,payload jsonb);
   create table logistics_dataset(id uuid primary key,status text);
   create table logistics_provider(id uuid primary key,payload jsonb);
   create table logistics_channel(id uuid primary key,provider_id uuid,dataset_id uuid,current_version_id uuid,code text,payload jsonb,archived_at timestamptz);
   create table logistics_company_binding(channel_id uuid,company_channel_id uuid);
   create table logistics_version(id uuid primary key,channel_id uuid,status text);
   insert into logistics_dataset values(md5('d')::uuid,'active');
   insert into logistics_provider values(md5('p')::uuid,'{"name":"物流甲"}'),(md5('p2')::uuid,'{"name":"物流乙"}');
   insert into logistics_channel values(md5('c')::uuid,md5('p')::uuid,md5('d')::uuid,md5('v')::uuid,'C1','{"name":"标准专线"}',null),
    (md5('c2')::uuid,md5('p2')::uuid,md5('d')::uuid,md5('v2')::uuid,'C2','{"name":"标准专线"}',null);
   insert into logistics_version values(md5('v')::uuid,md5('c')::uuid,'published'),(md5('draft')::uuid,md5('c')::uuid,'draft');
   insert into purchase_product values('SKU1','{"category":"服装"}'),('SKU2','{"category":"日用品"}');
   insert into quotation_record select md5('q'||i)::uuid,
    jsonb_build_object('no','Q-'||i,'primarySku','SKU1','customerName','真实结构客户','salespersonName','甲业务员',
     'salespersonAccount','SALE-A','country','美国','systemQuoteUsd',20,'financeReviewStatus','approved','status','won',
     'quoteOptions',jsonb_build_array(
      jsonb_build_object('country','美国','carrier','物流甲','channel','标准专线','logisticsChannelId',md5('c')::uuid,'quote1Usd',20,'quote2Usd',30),
      jsonb_build_object('country','美国','carrier','物流甲','channel','标准专线','logisticsChannelId',md5('c')::uuid,'quote3Usd',40),
      jsonb_build_object('country','US','carrier','物流甲','channel','标准专线','channelCode','C1','quote1Usd',20),
      jsonb_build_object('country','德国','carrier','物流甲','channel','标准专线','logisticsChannelId',md5('c')::uuid,'quote1Usd',30),
      jsonb_build_object('country','美国','carrier','物流乙','channel','标准专线','quote1Usd',25),
      jsonb_build_object('country','英国','carrier','物流甲','channel','标准专线','quote1Usd',20,'available',false),
      jsonb_build_object('country','加拿大','carrier','物流甲','channel','标准专线','quote1Usd',null),
      jsonb_build_object('country','法国','carrier','未知商','channel','未知专线','quote1Usd',20))),
    '2026-11-18T16:30:00Z',case when i=3 then 'trashed' when i=4 then 'withdrawn' when i=2 then 'archived' else 'active' end
    from generate_series(1,4)i;
   """);
  var service=new QuotationChannelRanking(ds,new ObjectMapper());
  var f=new QuotationChannelRanking.Filters(LocalDate.of(2026,11,19),LocalDate.of(2026,11,19),"","","","");
  var baseline=jdbc.queryForList("select id,payload::text,created_at,lifecycle_state from quotation_record order by id");
  var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setReadOnly(true);
  var result=tx.execute(status->service.read(f,0,5,false));
  assertEquals(8,result.totalUses());assertEquals(4,result.totalGroups());assertEquals(2,result.pendingGroups());assertEquals(1,result.unmatchedGroups());
  assertEquals(4,result.items().size());assertTrue(result.items().stream().allMatch(r->r.uses()==2&&r.share()==25));
  assertEquals(2,result.items().stream().filter(r->r.country().equals("美国")).count());
  var pending=service.read(f,0,5,true);
  assertEquals(2,pending.total());assertEquals(8,pending.totalUses());assertEquals(25,pending.items().getFirst().share());
  var usa=service.read(new QuotationChannelRanking.Filters(f.startDate(),f.endDate(),"US","甲业务员","服装","sku1"),0,5,false);
  assertEquals(4,usa.totalUses());assertEquals(2,usa.totalGroups());
  assertEquals(0,service.read(new QuotationChannelRanking.Filters(f.startDate(),f.endDate(),"","","日用品",""),0,5,false).totalUses());
  assertEquals(0,service.read(new QuotationChannelRanking.Filters(LocalDate.of(2026,11,18),LocalDate.of(2026,11,18),"","","",""),0,5,false).totalUses());
  assertEquals(baseline,jdbc.queryForList("select id,payload::text,created_at,lifecycle_state from quotation_record order by id"));
  // Missing-ID legacy snapshots are counted; authoritative empty modern arrays are not invented.
  jdbc.execute("""
   insert into quotation_record values(md5('legacy')::uuid,'{"country":"美国","carrier":"物流甲","channel":"标准专线","systemQuoteUsd":12,"primarySku":"SKU1"}','2026-11-19T01:00:00Z','active'),
    (md5('empty')::uuid,'{"country":"美国","carrier":"物流甲","channel":"标准专线","systemQuoteUsd":12,"quoteOptions":[]}','2026-11-19T01:00:00Z','active'),
    (md5('specified')::uuid,'{"specifiedQuotes":[{"country":"美国","carrier":"物流甲","channel":"标准专线","quoteCustomUsd":22}]}','2026-11-19T01:00:00Z','active'),
    (md5('fob')::uuid,'{"quoteMode":"fob","country":"美国","carrier":"物流甲","channel":"标准专线","systemQuoteUsd":12}','2026-11-19T01:00:00Z','active');
   """);
  assertEquals(10,service.read(f,0,5,false).totalUses());
  // Reclassification is observed immediately; no cache can retain removed records.
  jdbc.execute("update quotation_record set lifecycle_state='trashed' where id=md5('legacy')::uuid");
  assertEquals(9,service.read(f,0,5,false).totalUses());
  assertThrows(RuntimeException.class,()->service.read(f,0,10000,false));
  assertThrows(RuntimeException.class,()->service.read(new QuotationChannelRanking.Filters(f.endDate(),f.startDate().minusDays(1),"","","",""),0,5,false));
  // Large saved snapshots must remain on the server: only the requested aggregate page leaves it.
  jdbc.execute("""
   insert into quotation_record select md5('load'||i)::uuid,
     (select payload from quotation_record where id=md5('q1')::uuid),
     '2026-11-19T02:00:00Z','active' from generate_series(1,10000)i
   """);
  jdbc.execute("analyze quotation_record");
  long started=System.nanoTime();
  var large=tx.execute(status->service.read(f,0,5,false));
  long elapsed=(System.nanoTime()-started)/1_000_000;
  assertEquals(40009,large.totalUses());assertEquals(4,large.items().size());
  System.out.println("CHANNEL_RANKING_10000_RECORDS_MS="+elapsed);
  assertTrue(elapsed<6000,"10k-record aggregate exceeded the statement deadline");
  assertTrue(new ObjectMapper().writeValueAsString(large).length()<5000,"Response must contain aggregates, not historical quote details");
 }
}
