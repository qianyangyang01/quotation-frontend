package com.milano.quotation.quote;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class QuotationRecordColumnFiltersPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    private final ObjectMapper mapper=new ObjectMapper();
    private NamedParameterJdbcTemplate jdbc;
    private QuotationRecordQuery query;
    @BeforeEach void setup() {
        jdbc=new NamedParameterJdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        jdbc.getJdbcTemplate().execute("drop table if exists quotation_review,quotation_record");
        jdbc.getJdbcTemplate().execute("create table quotation_record(id uuid primary key,quote_no text,owner_account text,status text,payload jsonb,version bigint,created_at timestamptz,lifecycle_state text default 'active')");
        jdbc.getJdbcTemplate().execute("create table quotation_review(id uuid primary key,status text,claimant_account text,state jsonb,version bigint)");
        query=new QuotationRecordQuery(jdbc,mapper);
    }
    private ObjectNode record(String name, double customerPrice) {
        var value=mapper.createObjectNode().put("id",UUID.randomUUID().toString()).put("no",name)
            .put("customerName","Alice 100%_").put("productSummary","旅行枕").put("primarySku","SKU-ONE")
            .put("productCategory","家纺").put("salespersonName","张三").put("quoteConfirmed",true);
        value.putArray("quoteOptions").addObject().put("id","us").put("country","美国").put("carrier","云途")
            .put("channel","服装专线").put("channelCode","US-01").put("quote1Usd",10).put("quote2Usd",18);
        var snapshot=value.putObject("customerQuote");snapshot.putArray("quantities").add(1);
        snapshot.putArray("rows").addObject().put("optionId","us").putArray("prices").add(customerPrice);
        return value;
    }
    private void save(ObjectNode value,String owner) {
        jdbc.update("insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at) values(cast(:id as uuid),:no,:owner,'pending',cast(:payload as jsonb),0,'2026-09-24 01:00:00+00')",
            Map.of("id",value.path("id").asText(),"no",value.path("no").asText(),"owner",owner,"payload",value.toString()));
    }
    private QuotationRecordQuery.Filters filters(String product,String customer,String channel,String scale,String difference) {
        return new QuotationRecordQuery.Filters("","","","",null,null,"active","ME","",false,product,customer,channel,scale,difference);
    }
    private Set<String> names(String difference) {
        return query.search("ME",filters("","","","",difference),0,100).items().stream()
            .map(row->row.path("no").asText()).collect(java.util.stream.Collectors.toSet());
    }
    @Test void independentLiteralTextFiltersIncludeProductsBundlesAndChannelsWithoutCrossColumnMatches() {
        var row=record("QT-SEARCH",9);row.putArray("bundleItems").addObject().put("sku","BUNDLE+02").put("name","收纳袋");save(row,"ME");
        save(record("QT-OTHER",9).put("customerName","张三"),"OTHER");
        for(var product:List.of("qt-sea","旅行","sku-on","bundle+02","收纳","张三"))
            assertEquals(1,query.search("ME",filters(product," alice 100%_ ","us-01","single","lower"),0,10).total(),product);
        assertEquals(0,query.search("ME",filters("Alice","","","",""),0,10).total());
        assertEquals(0,query.search("ME",filters("","旅行枕","","",""),0,10).total());
        assertEquals(0,query.search("ME",filters("","100__","","",""),0,10).total());
        assertEquals(1,query.search("ME",filters("","","云途","",""),0,10).total());
        assertEquals(1,query.search("ME",filters("","","装专","",""),0,10).total());
    }
    @Test void usesSavedQuantityBaselineAndAnyChangedCellIncludingMixedAndMissingPrices() {
        save(record("LOW",9),"ME");save(record("EQUAL",10),"ME");save(record("HIGH",11),"ME");
        var mixed=record("MIXED",9);((ObjectNode)mixed.path("customerQuote")).putArray("quantities").add(1).add(2);
        ((ObjectNode)mixed.path("customerQuote").path("rows").get(0)).putArray("prices").add(9).add(20);save(mixed,"ME");
        var custom=record("CUSTOM",10);custom.put("customQuoteQuantity",7);
        ((ObjectNode)custom.path("customerQuote")).putArray("quantities").add(7);
        var system=custom.putObject("systemQuantityQuotes");system.putArray("quantities").add(2).add(7);
        system.putArray("rows").addObject().put("optionId","us").putArray("prices").add(18).add(15);save(custom,"ME");
        var blank=record("BLANK",10);((ObjectNode)blank.path("customerQuote").path("rows").get(0)).putArray("prices").addNull();save(blank,"ME");
        var baseline=record("NO-BASELINE",10);((ObjectNode)baseline.path("quoteOptions").get(0)).putNull("quote1Usd");save(baseline,"ME");
        var both=record("BOTH-NULL",10);((ObjectNode)both.path("quoteOptions").get(0)).putNull("quote1Usd");((ObjectNode)both.path("customerQuote").path("rows").get(0)).putArray("prices").addNull();save(both,"ME");
        var sheet=record("SHEET",8);sheet.set("sheetQuote",sheet.remove("customerQuote"));save(sheet,"ME");
        var original=record("ORIGINAL",1);original.remove("customerQuote");save(original,"ME");
        assertEquals(Set.of("LOW","MIXED","CUSTOM","SHEET"),names("lower"));
        assertEquals(Set.of("HIGH","MIXED"),names("higher"));
        assertEquals(Set.of("EQUAL","BOTH-NULL","ORIGINAL"),names("equal"));
        assertEquals(Set.of("BLANK","NO-BASELINE"),names("missing"));
    }
    @Test void preservesOwnerLifecycleReviewAndStatisticsAcrossAllExportPages() {
        for(int i=0;i<105;i++)save(record("LOW-"+i,9).put("financeReviewStatus","approved"),"ME");
        save(record("OTHER",9).put("financeReviewStatus","approved"),"OTHER");
        save(record("HIGH",11).put("financeReviewStatus","approved"),"ME");
        save(record("PENDING",9),"ME");
        save(record("ARCHIVED",9).put("financeReviewStatus","approved"),"ME");
        jdbc.getJdbcTemplate().execute("update quotation_record set lifecycle_state='archived' where quote_no='ARCHIVED'");
        var filters=new QuotationRecordQuery.Filters("","processed","美国","家纺",java.time.LocalDate.parse("2026-09-24"),java.time.LocalDate.parse("2026-09-24"),"active","ME","approved",false,"","alice","专线","single","lower");
        var first=query.search("ME",filters,0,100);var second=query.search("ME",filters,1,100);
        assertEquals(105,first.total());assertEquals(105,first.summary().processed());assertEquals(0,first.summary().pending());
        assertEquals(100,first.items().size());assertEquals(5,second.items().size());assertEquals(2,first.totalPages());
        var ids=new HashSet<String>();first.items().forEach(row->ids.add(row.path("id").asText()));second.items().forEach(row->ids.add(row.path("id").asText()));assertEquals(105,ids.size());
        assertEquals(106,query.search(null,filters,0,100).total());
        assertEquals(1,query.search("ME",filters,99,100).page());
        assertEquals(0,query.search("ME",filters("not-found","","","","lower"),0,10).total());
    }
    @Test void countsLegacyAndModernOptionsAndRejectsUnknownFilterValues() {
        save(record("SINGLE",9),"ME");
        var multiple=record("MULTI",9);multiple.withArray("quoteOptions").addObject().put("id","uk");save(multiple,"ME");
        var legacy=record("LEGACY",9);legacy.set("specifiedQuotes",legacy.remove("quoteOptions"));
        ((ObjectNode)legacy.path("customerQuote").path("rows").get(0)).put("optionId","legacy-"+legacy.path("id").asText()+"-0");
        ((ObjectNode)legacy.path("specifiedQuotes").get(0)).put("quote1Usd","10.00");save(legacy,"ME");
        var empty=record("EMPTY",9);empty.putArray("quoteOptions");empty.remove("customerQuote");save(empty,"ME");
        var old=record("OLD",9);old.remove("quoteOptions");old.remove("customerQuote");save(old,"ME");
        assertEquals(1,query.search("ME",filters("","","","multiple",""),0,100).total());
        assertEquals(4,query.search("ME",filters("","","","single",""),0,100).total());
        assertTrue(names("lower").contains("LEGACY"));assertTrue(names("equal").containsAll(Set.of("EMPTY","OLD")));
        assertThrows(RuntimeException.class,()->query.search("ME",filters("","","","bad",""),0,10));
        assertThrows(RuntimeException.class,()->query.search("ME",filters("","","","","bad"),0,10));
    }
}
