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
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties="app.purchase-sales.source=classpath:purchase-sales-test.json")
@ActiveProfiles("test") @Testcontainers(disabledWithoutDocker=true)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
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
    private String fingerprint() {return jdbc.sql("select md5(string_agg(v,'|' order by v)) from (select payload::text||version::text as v from quotation_record union all select state::text||version::text from quotation_review union all select payload::text||version::text from purchase_product union all select state::text||version::text from batch_single_review) t").query(String.class).single();}
    @Test @WithMockUser(authorities={"PERM_purchase","ROLE_PURCHASE"})
    void projectsAllReviewShapesDeduplicatesAndNeverWritesBusinessRecords() throws Exception {
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
        var mvc=webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/v1/purchase-rankings")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/purchase-rankings?days=-1")).andExpect(status().isUnprocessableEntity());
        assertEquals(before,fingerprint());
    }
    @Test @WithMockUser(authorities="PERM_quote") void deniesUsersWithoutPurchaseAccess() throws Exception {
        webAppContextSetup(context).apply(springSecurity()).build().perform(get("/api/v1/purchase-rankings")).andExpect(status().isForbidden());
    }
}
