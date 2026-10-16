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
    @BeforeEach void setup() throws Exception {
        jdbc=new NamedParameterJdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        jdbc.getJdbcTemplate().execute("drop table if exists quotation_review,quotation_record");
        jdbc.getJdbcTemplate().execute("create table quotation_record(id uuid primary key,quote_no text,owner_account text,status text,payload jsonb,version bigint,created_at timestamptz,lifecycle_state text default 'active')");
        jdbc.getJdbcTemplate().execute("create table quotation_review(id uuid primary key,status text,claimant_account text,state jsonb,version bigint)");
        jdbc.getJdbcTemplate().execute("drop function if exists quotation_product_search_text(text,jsonb)");
        try (var connection = jdbc.getJdbcTemplate().getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                new org.springframework.core.io.ClassPathResource("db/migration/V62__quotation_product_search.sql"));
        }
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
    @Test void indexedProductFilterRetainsLiteralWildcardsShortNamesBatchAndLegacySemantics() {
        var row=record("QT-LITERAL",9).put("primarySku","SKU-100%_\\CASE");
        row.putObject("quotationBatch").put("no","BQT-ONE");
        row.putArray("bundleItems").addObject().put("sku","BUNDLE+02").put("name","收纳袋");
        save(row,"ME");
        var legacy=record("QT-LEGACY",10);legacy.remove("primarySku");legacy.put("bundleItems","old-format");save(legacy,"OTHER");
        for(var term:List.of("100%_", "%", "_", "\\", "CASE", "张", "张三", "bqt-one", "收纳", "BUNDLE+02"))
            assertEquals(1,query.search("ME",filters(term,"","","",""),0,10).total(),term);
        assertEquals(0,query.search("ME",filters("100__","","","",""),0,10).total());
        assertEquals(0,query.search("ME",filters("Alice","","","",""),0,10).total());
        assertEquals(1,query.search("OTHER",filters("qt-legacy","","","",""),0,10).total());
        jdbc.getJdbcTemplate().execute("update quotation_record set payload=jsonb_set(payload,'{salespersonName}','\"李四\"') where quote_no='QT-LITERAL'");
        assertEquals(0,query.search("ME",filters("张三","","","",""),0,10).total());
        assertEquals(1,query.search("ME",filters("李四","","","",""),0,10).total());
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

    @Test void shippingNotesUseIndexedLiteralSearchWithFilteredCountsAndEveryExportPageWithoutMutatingHistory() {
        for (int i = 0; i < 105; i++) {
            var row = record("SHIPPING-" + i, 9).put("quoteMode", "shipping-only").put("primarySku", "")
                .put("shippingNote", "TC2600009-1 蓝色 客户自备 100%_/A\\B").put("financeReviewStatus", "approved");
            ShippingQuotationNote.snapshot(row); save(row, "ME");
        }
        var otherOwner = record("OTHER-OWNER", 9).put("quoteMode", "shipping-only").put("primarySku", "")
            .put("shippingNote", "TC2600009-1 蓝色 客户自备 100%_/A\\B").put("financeReviewStatus", "approved");
        ShippingQuotationNote.snapshot(otherOwner); save(otherOwner, "OTHER");
        var archived = otherOwner.deepCopy().put("id", UUID.randomUUID().toString()).put("no", "ARCHIVED");
        save(archived, "ME");
        jdbc.getJdbcTemplate().execute("update quotation_record set lifecycle_state='archived' where quote_no='ARCHIVED'");
        save(record("LEGACY-SHIPPING", 10).put("quoteMode", "shipping-only").put("primarySku", "").put("productSummary", "仅代发货报价"), "ME");
        save(record("PRODUCT", 9).put("quoteMode", "single").put("productSummary", "TC2600009-1 蓝色 客户自备 100%_/A\\B"), "ME");
        var before = jdbc.getJdbcTemplate().queryForObject("select md5(string_agg(to_jsonb(q)::text,'' order by id)) from quotation_record q", String.class);
        for (var term : List.of("tc2600009-1", "TC2600009", "蓝色", "客户自备", "100%_", "%", "_", "/A", "\\B")) {
            var filter = shippingFilters(term, "", "active");
            var first = query.search("ME", filter, 0, 100); var second = query.search("ME", filter, 1, 100);
            assertEquals(105, first.total(), term); assertEquals(105, first.summary().processed(), term);
            assertEquals(100, first.items().size(), term); assertEquals(5, second.items().size(), term);
            assertEquals(2, first.totalPages(), term);
            var ids = new HashSet<String>(); first.items().forEach(row -> ids.add(row.path("id").asText()));
            second.items().forEach(row -> ids.add(row.path("id").asText())); assertEquals(105, ids.size(), term);
            assertTrue(first.items().stream().allMatch(row -> row.path("primarySku").asText().isEmpty()));
            assertEquals(105, query.search("ME", shippingFilters("", term, "active"), 0, 100).total(), "global " + term);
        }
        assertEquals(0, query.search("ME", shippingFilters("100__", "", "active"), 0, 10).total());
        assertEquals(106, query.search(null, shippingFilters("tc2600009", "", "active"), 0, 100).total());
        assertEquals(1, query.search("ME", shippingFilters("tc2600009", "", "archived"), 0, 100).total());
        assertEquals(before, jdbc.getJdbcTemplate().queryForObject("select md5(string_agg(to_jsonb(q)::text,'' order by id)) from quotation_record q", String.class));
    }

    private QuotationRecordQuery.Filters shippingFilters(String product, String q, String lifecycle) {
        return new QuotationRecordQuery.Filters(q, "processed", "美国", "", java.time.LocalDate.parse("2026-09-24"),
            java.time.LocalDate.parse("2026-09-24"), lifecycle, "ME", "approved", false, product, "alice", "专线", "single", "lower", false, "", "shipping-only");
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

    @Test void averagePriceDifferencesParticipateInFiltersSummaryAndPagedExport() {
        for (int i=0;i<105;i++) {
            var row=record("AVG-"+i,10);
            var plan=((ObjectNode)row.path("customerQuote")).putArray("averagePlans").addObject();
            plan.putArray("quantities").add(1); plan.putArray("systemPrices").add(5.14); plan.putArray("prices").add(4.8);
            save(row,"ME");
        }
        for(var name: List.of("HIGH","MISSING","EQUAL")) {
            var row=record(name,10); var plan=((ObjectNode)row.path("customerQuote")).putArray("averagePlans").addObject();
            plan.putArray("quantities").add(1); plan.putArray("systemPrices").add(5.14);
            var prices=plan.putArray("prices");if(name.equals("MISSING"))prices.addNull();else prices.add(name.equals("HIGH")?6:5.14);
            save(row,"ME");
        }
        var first=query.search("ME",filters("","","","","lower"),0,100);
        var second=query.search("ME",filters("","","","","lower"),1,100);
        assertEquals(105,first.total());assertEquals(100,first.items().size());assertEquals(5,second.items().size());
        assertEquals(Set.of("HIGH"),names("higher"));assertEquals(Set.of("MISSING"),names("missing"));assertEquals(Set.of("EQUAL"),names("equal"));
    }
}
