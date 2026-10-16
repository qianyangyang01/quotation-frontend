package com.milano.quotation.purchase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties="app.purchase-sales.source=classpath:purchase-sales-test.json")
@ActiveProfiles("test") @Testcontainers(disabledWithoutDocker=true)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.transaction.annotation.Transactional
class PurchaseRankingPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);r.add("spring.sql.init.mode",()->"never");
        r.add("spring.jpa.hibernate.ddl-auto",()->"validate");r.add("spring.jpa.defer-datasource-initialization",()->"false");r.add("spring.flyway.enabled",()->"true");
    }
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PurchaseProductService products;
    @Autowired PurchaseRankingService rankings;
    @Autowired WebApplicationContext context;
    @Autowired com.milano.quotation.security.UserAccountRepository accounts;
    private void account(String account,String name,String role) {
        accounts.saveAndFlush(com.milano.quotation.security.UserAccount.create(account,name,"unused-test-hash",role,false));
    }
    private ObjectNode exception(String owner,String source) {
        var c=mapper.createObjectNode().put("category","purchase-price").put("recordedAt",Instant.now().toString());
        c.putArray("targets").addObject().put("sku","SALES-PRIMARY").put("source",source).put("procurementOwner",owner);
        return c;
    }
    private void quote(ObjectNode payload,ObjectNode state) {
        var id=UUID.randomUUID();
        jdbc.sql("insert into quotation_record(id,quote_no,owner_account,status,payload,created_at,updated_at) values(:id,:no,'QA','saved',cast(:payload as jsonb),now(),now())")
            .param("id",id).param("no",id.toString()).param("payload",payload.toString()).update();
        jdbc.sql("insert into quotation_review(id,status,state,version) values(:id,'rejected',cast(:state as jsonb),0)")
            .param("id",id).param("state",state.toString()).update();
    }
    private String fingerprint() {return jdbc.sql("select md5(string_agg(v,'|' order by v)) from (select payload::text||version::text as v from quotation_record union all select state::text||version::text from quotation_review union all select payload::text||version::text from purchase_product union all select payload::text||version::text from fob_purchase_product union all select state::text||version::text from batch_single_review union all select account||display_name||role_key||status||version::text from app_user) t").query(String.class).single();}
    @Test @WithMockUser(authorities={"PERM_purchase","ROLE_PURCHASE"})
    void projectsAllReviewShapesDeduplicatesAndNeverWritesBusinessRecords() throws Exception {
        account("CURRENTBUYER","当前采购","purchase");account("OLDBUYER","历史采购","purchase");
        account("FOBBUYER","FOB采购","purchase");account("BATCHBUYER","批量采购","purchase");
        products.upsert(mapper.createObjectNode().put("sku","SALES-PRIMARY").put("quotationOwner","当前采购")
            .put("weightG",100).put("minOrderQty",1).put("purchasePriceCny",12).put("singleFreightCny",0).put("taxPoint",0));
        var state=mapper.createObjectNode();var history=state.putArray("history");
        for(int i=0;i<2;i++)history.addObject().put("action","complete").put("after","rejected").put("at",Instant.now().toString()).set("priceException",exception("历史采购","standard"));
        quote(mapper.createObjectNode().put("primarySku","SALES-PRIMARY"),state);
        var fob=mapper.createObjectNode().put("quoteMode","fob");fob.putObject("fob").put("schemaVersion",2).putArray("items").addObject().putObject("product").put("sku","SALES-PRIMARY").put("source","fob");
        var fobState=mapper.createObjectNode();var event=fobState.putArray("history").addObject().put("action","fob-return").put("at",Instant.now().toString());
        event.putArray("itemChanges").addObject().put("index",0).put("status","rejected").set("priceException",exception("FOB采购","fob"));
        event.putArray("itemReviews").addObject().set("priceException",exception("不得计数","fob"));quote(fob,fobState);
        var batch=UUID.randomUUID();var payload=mapper.createObjectNode();payload.putArray("items").addObject().putObject("input").put("primarySku","SALES-PRIMARY");
        jdbc.sql("insert into batch_single_quotation(id,quote_no,owner_account,created_at,payload) values(:id,:no,'QA',now(),cast(:payload as jsonb))").param("id",batch).param("no",batch.toString()).param("payload",payload.toString()).update();
        var batchState=mapper.createObjectNode();batchState.putArray("history").addObject().put("action","complete").put("at",Instant.now().toString()).putArray("items").addObject().put("index",0).put("sku","SALES-PRIMARY").putObject("after").put("status","rejected").set("priceException",exception("批量采购","standard"));
        jdbc.sql("insert into batch_single_review(record_id,version,state) values(:id,0,cast(:state as jsonb))").param("id",batch).param("state",batchState.toString()).update();
        var legacy=mapper.createObjectNode().put("financeReviewStatus","rejected").put("financeReviewedAt",Instant.now().toString());quote(mapper.createObjectNode().put("primarySku","SALES-PRIMARY"),legacy);
        String before=fingerprint();
        var result=rankings.get("all","all",30);
        assertEquals(3,result.exceptions().stream().mapToLong(PurchaseRankingService.Exceptions::count).sum());
        assertTrue(result.exceptions().stream().noneMatch(x->x.owner().equals("不得计数")));
        assertEquals(1,result.unclassified());assertEquals(1,result.completion().getFirst().total());
        assertEquals(100,result.completion().getFirst().rate());
        var sales=rankings.get("sales","all",30);assertEquals(2,sales.unmatchedSalesSkus());
        var legacyProjection=legacyReviews().stream().collect(java.util.stream.Collectors.groupingBy(Object::toString,java.util.stream.Collectors.counting()));
        var projected=rankings.readReviews(owners(),Instant.EPOCH,Instant.now()).stream()
                .collect(java.util.stream.Collectors.groupingBy(Object::toString,java.util.stream.Collectors.counting()));
        assertEquals(legacyProjection,projected,"ordinary, legacy, FOB item and batch projections remain identical");
        var mvc=webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/v1/purchase-rankings")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/purchase-rankings?days=-1")).andExpect(status().isUnprocessableEntity());
        assertEquals(before,fingerprint());
    }
    @Test void filtersByRoleMergesAliasesAndKeepsHistoricalOwners() {
        account("ZHENGKE","郑珂","purchase");account("RANKADMIN","管理员","super_admin");
        account("RANKEMPLOYEE","普通员工","employee");
        for(var entry:java.util.Map.of("ALIAS","郑","FULL","郑珂","ACCOUNT","zhengke","ADMIN","管理员","EMPLOYEE","普通员工","UNKNOWN","感温款").entrySet())
            products.upsert(mapper.createObjectNode().put("sku",entry.getKey()).put("quotationOwner",entry.getValue())
                .put("weightG",100).put("minOrderQty",1).put("purchasePriceCny",12).put("singleFreightCny",0).put("taxPoint",0));
        for(var entry:java.util.Map.of("ALIAS","郑珂","ACCOUNT","管理员").entrySet()) {
            var payload=mapper.readTree("""
                {"weightRaw":"100g","parsed":{"minOrderQty":1,"priceTiers":[{}],"freight":{"totalFreightCny":0}}}
                """);
            ((ObjectNode)payload).put("quotationOwner",entry.getValue());
            jdbc.sql("insert into fob_purchase_product(sku,payload,created_at,updated_at) values(:sku,cast(:payload as jsonb),now(),now())")
                .param("sku",entry.getKey()).param("payload",payload.toString()).update();
        }
        for(var owner:java.util.List.of("郑","管理员","感温款")) {
            var state=mapper.createObjectNode();
            state.putArray("history").addObject().put("action","complete").put("after","rejected").put("at",Instant.now().toString()).set("priceException",exception(owner,"standard"));
            quote(mapper.createObjectNode().put("primarySku","SALES-PRIMARY"),state);
        }
        String before=fingerprint();
        var result=rankings.get("all","all",30);
        assertEquals(1,result.completion().size());assertEquals("郑珂",result.completion().getFirst().owner());
        assertEquals(2,result.completion().getFirst().total());assertEquals(2,result.completion().getFirst().complete());
        assertEquals(3,rankings.get("all","standard",30).completion().getFirst().total());
        assertEquals(1,result.exceptions().size());assertEquals(1,result.exceptions().getFirst().count());
        assertEquals(1,result.exceptions().getFirst().relatedQuotes());assertEquals(before,fingerprint());
        jdbc.sql("update app_user set status='disabled' where account='ZHENGKE'").update();
        assertEquals(2,rankings.get("all","all",30).completion().getFirst().total());
        jdbc.sql("update app_user set role_key='employee' where account='ZHENGKE'").update();
        assertTrue(rankings.get("all","all",30).completion().isEmpty());
        assertTrue(rankings.get("all","all",30).exceptions().isEmpty());
    }
    @Test @WithMockUser(authorities="PERM_quote") void deniesUsersWithoutPurchaseAccess() throws Exception {
        webAppContextSetup(context).apply(springSecurity()).build().perform(get("/api/v1/purchase-rankings")).andExpect(status().isForbidden());
    }

    private PurchaseRankingOwners owners() {
        return new PurchaseRankingOwners(jdbc.sql("select account,display_name,role_key from app_user")
                .query((rs,n)->new PurchaseRankingOwners.Account(rs.getString(1),rs.getString(2),rs.getString(3))).list());
    }

    private java.util.List<PurchaseRankingService.Review> legacyReviews() throws Exception {
        String sql;
        try(var input=getClass().getResourceAsStream("/purchase-ranking-legacy-reviews.sql")) {
            assertNotNull(input);sql=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replace("\uFEFF","");
        }
        var owners=owners();
        return jdbc.sql(sql).query((rs,n)->new PurchaseRankingService.Review(rs.getString("id"),mapper.readTree(rs.getString("refs")),
                PurchaseRankingService.resolveEvent(mapper.readTree(rs.getString("event")),owners))).list();
    }

    @Test void productProjectionPreservesEveryJsonTypeMissingFieldsAndNonObjects() {
        account("JSONBUYER","类型采购","purchase");
        String[] samples={"{}","null","[]","12","\"text\"", """
          {"quotationOwner":"类型采购","weightG":"100","minOrderQty":1,"purchasePriceCny":0,
           "taxIncludedPriceCny":null,"tier2PriceCny":[],"tier3PriceCny":{},"taxPoint":0.1300,
           "invoiceType":true,"singleFreightCny":0,"freeShipping":"是","dataSource":"legacy_2026","skuOrigin":"manual",
           "weightRaw":"100g","parsed":{"minOrderQty":1,"priceTiers":[{"price":1.2500}],"freight":{"totalFreightCny":0}}}
          """};
        for(int i=0;i<samples.length;i++) {
            jdbc.sql("insert into purchase_product(id,sku,payload,created_at,updated_at) values(:id,:sku,cast(:payload as jsonb),now(),now())")
                .param("id",UUID.randomUUID()).param("sku","JSON-"+i).param("payload",samples[i]).update();
            jdbc.sql("insert into fob_purchase_product(sku,payload,created_at,updated_at) values(:sku,cast(:payload as jsonb),now(),now())")
                .param("sku","JSON-"+i).param("payload",samples[i]).update();
        }
        var projected=rankings.readProducts("all","all",owners());
        var standard=java.util.List.of("quotationOwner","weightG","minOrderQty","purchasePriceCny","taxIncludedPriceCny","tier2PriceCny","tier3PriceCny","taxPoint","invoiceType","singleFreightCny","freeShipping","dataSource","skuOrigin");
        var fob=java.util.List.of("quotationOwner","weightRaw","parsed");
        for(var source:java.util.List.of("standard","fob")) {
            var fields=source.equals("standard")?standard:fob;
            String pairs=fields.stream().map(field->"'"+field+"',payload->'"+field+"'").collect(java.util.stream.Collectors.joining(","));
            String table=source.equals("standard")?"purchase_product":"fob_purchase_product";
            var expected=jdbc.sql("select sku,jsonb_build_object("+pairs+")::text as data from "+table+" where sku like 'JSON-%'")
                    .query((rs,n)->java.util.Map.entry(rs.getString("sku"),mapper.readTree(rs.getString("data")))).list();
            assertEquals(samples.length,expected.size());
            for(var row:expected)assertEquals(row.getValue(),projected.stream().filter(p->p.source().equals(source)&&p.sku().equals(row.getKey())).findFirst().orElseThrow().data());
        }
        var stringWeight=projected.stream().filter(p->p.source().equals("standard")&&p.sku().equals("JSON-5")).findFirst().orElseThrow();
        assertTrue(stringWeight.data().path("weightG").isTextual());
        assertFalse(PurchaseRankingService.complete(stringWeight));
    }

    @Test void reviewSqlMatchesLegacyAcrossExactNanosecondBoundsInvalidDatesAndOtherIsoFormats() throws Exception {
        account("WINDOWBUYER","窗口采购","purchase");
        products.upsert(mapper.createObjectNode().put("sku","SALES-PRIMARY").put("quotationOwner","窗口采购")
                .put("weightG",100).put("minOrderQty",1).put("purchasePriceCny",12).put("singleFreightCny",0).put("taxPoint",0));
        var from=Instant.parse("2026-10-01T00:00:00.123456789Z");
        var to=Instant.parse("2026-10-16T00:00:00.987654321Z");
        var dates=java.util.List.of(from.minusNanos(1).toString(),from.toString(),from.plusNanos(1).toString(),
                to.minusNanos(1).toString(),to.toString(),to.plusNanos(1).toString(),
                "2026-09-01T00:00:00Z","2026-11-01T00:00:00Z","2026-02-30T00:00:00Z","bad-time","",
                "2026-10-01T08:00:00.123456789+08:00","2026-10-02t00:00:00z","2026-10-02T23:59:60Z");
        for(var at:dates) {
            var state=mapper.createObjectNode();state.putArray("history").addObject().put("action","complete")
                    .put("after","rejected").put("at",at).set("priceException",exception("窗口采购","standard"));
            // Creation time is deliberately unrelated to the review event window.
            quote(mapper.createObjectNode().put("primarySku","SALES-PRIMARY"),state);
        }
        // An unreviewed malformed bundle was never expanded by the original query.
        quote(mapper.createObjectNode().put("quoteMode","bundle").put("bundleItems","not-an-array"),mapper.createObjectNode());
        var products=rankings.readProducts("all","all",owners());
        var original=legacyReviews();var optimized=rankings.readReviews(owners(),from,to);
        assertTrue(optimized.size()<original.size(),"valid events far outside the selected window are removed in SQL");
        var expected=PurchaseRankingService.aggregate("all","all",30,from,to,"",true,java.util.Set.of(),products,original);
        var actual=PurchaseRankingService.aggregate("all","all",30,from,to,"",true,java.util.Set.of(),products,optimized);
        assertEquals(expected,actual);
        assertEquals(3,actual.undated());
        assertEquals(7,actual.exceptions().getFirst().count());
        var legacyAll=original.stream().collect(java.util.stream.Collectors.groupingBy(Object::toString,java.util.stream.Collectors.counting()));
        var optimizedAll=rankings.readReviews(owners(),Instant.EPOCH,Instant.parse("2100-01-01T00:00:00Z"))
                .stream().collect(java.util.stream.Collectors.groupingBy(Object::toString,java.util.stream.Collectors.counting()));
        assertEquals(legacyAll,optimizedAll);
    }

    @Test @WithMockUser(authorities={"PERM_purchase","ROLE_PURCHASE"})
    void independentPartsReuseCompletionAcrossDaysInvalidateOnWritesAndKeepZeroExceptionOwners() throws Exception {
        account("CACHEBUYER","缓存采购","purchase");
        products.upsert(mapper.createObjectNode().put("sku","SALES-PRIMARY").put("quotationOwner","缓存采购")
                .put("weightG",100).put("minOrderQty",1).put("purchasePriceCny",12).put("singleFreightCny",0).put("taxPoint",0));
        var first=rankings.get("all","all",30,"completion",false);
        assertSame(first,rankings.get("all","all",7,"completion",false));
        assertEquals(0,first.days());assertTrue(first.exceptions().isEmpty());
        var exceptions=rankings.get("all","all",30,"exceptions",false);
        assertTrue(exceptions.completion().isEmpty());assertEquals("缓存采购",exceptions.exceptions().getFirst().owner());
        assertEquals(0,exceptions.exceptions().getFirst().count());
        assertSame(exceptions,rankings.get("all","all",30,"exceptions",false));
        assertNotSame(first,rankings.get("all","all",30,"completion",true));
        var state=mapper.createObjectNode();state.putArray("history").addObject().put("action","complete").put("after","rejected")
                .put("at",Instant.now().toString()).set("priceException",exception("缓存采购","standard"));
        quote(mapper.createObjectNode().put("primarySku","SALES-PRIMARY"),state);
        assertEquals(1,rankings.get("all","all",30,"exceptions",false).exceptions().getFirst().count());
        // An application edit bumps product version and invalidates both its catalog and results.
        long productVersion=jdbc.sql("select version from purchase_product where sku='SALES-PRIMARY'").query(Long.class).single();
        products.upsert(mapper.createObjectNode().put("sku","SALES-PRIMARY").put("quotationOwner","缓存采购")
                .put("_version",productVersion)
                .put("weightG",0).put("minOrderQty",1).put("purchasePriceCny",12).put("singleFreightCny",0).put("taxPoint",0));
        assertEquals(0,rankings.get("all","all",30,"completion",false).completion().getFirst().complete());
        jdbc.sql("update app_user set role_key='employee' where account='CACHEBUYER'").update();
        assertTrue(rankings.get("all","all",30,"completion",false).completion().isEmpty());
        assertTrue(rankings.get("all","all",30,"exceptions",false).exceptions().isEmpty());
        var mvc=webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/v1/purchase-rankings?part=completion&days=7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.days").value(0)).andExpect(jsonPath("$.data.exceptions").isEmpty());
        mvc.perform(get("/api/v1/purchase-rankings?part=exceptions&days=7&refresh=true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.days").value(7)).andExpect(jsonPath("$.data.completion").isEmpty());
        mvc.perform(get("/api/v1/purchase-rankings?part=other")).andExpect(status().isUnprocessableEntity());
    }
}
