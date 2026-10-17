package com.milano.quotation.purchase;

import com.milano.quotation.storage.AssetObjectRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker=true)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PurchaseForceDeletionPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.sql.init.mode",()->"never");r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("spring.jpa.defer-datasource-initialization",()->"false");r.add("spring.flyway.enabled",()->"true");
    }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate db;
    @Autowired PurchaseProductService products;
    @Autowired AssetObjectRepository assets;
    @Autowired ObjectMapper mapper;
    @Autowired com.milano.quotation.imports.PurchaseImportJdbcService importJdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    MockMvc mvc;
    @BeforeEach void setup(){mvc=webAppContextSetup(context).apply(springSecurity()).build();}
    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor admin(){
        return user("FORCEADMIN").authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),new SimpleGrantedAuthority("PERM_purchase"),new SimpleGrantedAuthority("PERM_allRecords"));
    }
    record Fixture(String sku,UUID product,UUID quote,UUID image,UUID job){}
    Fixture fixture() {
        var sku="FORCE-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT);
        var p=UUID.randomUUID();var q=UUID.randomUUID();var image=UUID.randomUUID();var job=UUID.randomUUID();
        var imageUrl="/api/v1/assets/"+image;
        var payload=mapper.createObjectNode().put("sku",sku).put("category","宠物用品").put("weightG",95).put("minOrderQty",1)
                .put("purchasePriceCny",4.28).put("productImage",imageUrl);
        db.update("insert into purchase_product(id,sku,payload,version,created_at,updated_at,catalog_state,quote_ready) values(?,?,?::jsonb,2,now(),now(),'disabled',false)",p,sku,payload.toString());
        db.update("insert into asset_object(id,sha256,object_key,media_type,size_bytes,original_name,storage_state,created_at) values(?,?,?,'image/png',1,'fixture.png','published',now())",image,image.toString().replace("-","").repeat(2),"force/"+image);
        db.update("insert into purchase_product_image(id,product_id,asset_id,image_type,sort_order) values(?,?,?,'product',0)",UUID.randomUUID(),p,image);
        var quote=mapper.createObjectNode().put("primarySku",sku).put("productImage",imageUrl).put("systemQuoteCny",88.80).put("actualQuoteCny",90)
                .put("financeReviewStatus","approved").put("productSummary","原商品快照");
        quote.putObject("weightSnapshot").put("weightG",95);
        db.update("insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at,lifecycle_state) values(?,?,?,'won',?::jsonb,9,now(),now(),'active')",q,"Q-"+q,"FORCEADMIN",quote.toString());
        db.update("insert into quotation_review(id,status,claimant_account,state,version) values(?,'approved',NULL,?::jsonb,5)",q,"{\"financeReviewStatus\":\"approved\",\"reviewedBy\":\"FINANCE\"}");
        db.update("insert into quotation_draft(owner_account,payload,version,updated_at) values(?,?::jsonb,7,now())",sku,"{\"product\":{\"sku\":\""+sku+"\"}}");
        db.update("insert into quotation_template(id,owner_account,name,payload,version,created_at,updated_at) values(?,?,'keep',?::jsonb,3,now(),now())",UUID.randomUUID(),sku,"{\"bundleItems\":[{\"sku\":\""+sku+"\"}]}");
        db.update("insert into import_job(id,job_type,status,requested_by,source_name,payload,created_at,updated_at) values(?,'purchase-xlsx-async','completed','FORCEADMIN','fixture.xlsx','{}',now(),now())",job);
        db.update("insert into purchase_import_row(id,job_id,source_row,sku,payload,created_at,validation_status,applied_product_id,applied_at,applied_version) values(?,?,2,?,'{}',now(),'valid',?,now(),2)",UUID.randomUUID(),job,sku,p);
        return new Fixture(sku,p,q,image,job);
    }
    String input(Fixture f,long version,String confirmed){return mapper.createObjectNode().put("expectedVersion",version).put("confirmationSku",confirmed).toString();}
    List<Map<String,Object>> preserved(Fixture f){
        var rows=new ArrayList<Map<String,Object>>();
        rows.addAll(db.queryForList("select * from quotation_record where id=?",f.quote));
        rows.addAll(db.queryForList("select * from quotation_review where id=?",f.quote));
        rows.addAll(db.queryForList("select * from quotation_draft where owner_account=?",f.sku));
        rows.addAll(db.queryForList("select * from quotation_template where owner_account=?",f.sku));
        rows.addAll(db.queryForList("select * from purchase_import_row where job_id=?",f.job));
        rows.addAll(db.queryForList("select * from finance_setting order by setting_key"));
        return rows;
    }
    @Test void forceDeletePreservesSavedRecordsReviewsDraftsTemplatesImportsAndImages() throws Exception {
        var f=fixture();var before=preserved(f);
        mvc.perform(delete("/api/v1/purchase-products/"+f.sku).param("expectedVersion","2").with(admin()).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/purchase-products/"+f.sku+"/force-delete").with(admin()).with(csrf()).contentType("application/json").content(input(f,2,f.sku))).andExpect(status().isOk());
        assertEquals(0,db.queryForObject("select count(*) from purchase_product where id=?",Integer.class,f.product));
        assertEquals(0,db.queryForObject("select count(*) from purchase_product_image where product_id=?",Integer.class,f.product));
        assertEquals(before,preserved(f));
        assertFalse(products.isQuoteReady(f.sku));
        assertEquals(1,importJdbc.lockAndCountRollbackConflicts(f.job));
        var archived=products.analyticsCatalog().items().stream().filter(item->item.path("sku").asText().equals(f.sku)).findFirst().orElseThrow();
        assertTrue(archived.path("archived").asBoolean());
        assertEquals("宠物用品",archived.path("categoryHistory").get(0).path("category").asText());
        assertTrue(archived.path("purchasePriceCny").isNull());
        assertEquals(0,new org.springframework.transaction.support.TransactionTemplate(transactions)
                .execute(status->assets.retireUnreferenced(List.of(f.image),Instant.now())));
        mvc.perform(get("/api/v1/purchase-products/"+f.sku).with(admin())).andExpect(status().isNotFound());
        var audit=mapper.readTree(db.queryForObject("select detail::text from audit_log where action='purchase.force-delete' and resource_id=?",String.class,f.sku));
        assertEquals(1,audit.path("quotationRecords").asInt());assertEquals(1,audit.path("drafts").asInt());
        assertEquals(1,audit.path("templates").asInt());assertEquals(1,audit.path("importBatches").asInt());
        assertEquals(f.sku,audit.path("productSnapshot").path("sku").asText());
        assertEquals(f.image.toString(),audit.path("retainedAssetIds").get(0).asText());
        // Even if an old cleanup task stages this image, both its saved quote and deletion snapshot pin it.
        db.update("update asset_object set storage_state='temporary',expires_at=now()-interval '1 day' where id=?",f.image);
        assertTrue(assets.findExpiredUnreferenced(Instant.now()).stream().noneMatch(a->a.id.equals(f.image)));
        mvc.perform(get("/api/v1/purchase-products/deleted-snapshots").param("skus",f.sku).with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].productImage").value("/api/v1/assets/"+f.image))
                .andExpect(jsonPath("$.data[0].category").value("宠物用品"))
                .andExpect(jsonPath("$.data[0].purchasePriceCny").doesNotExist());
        mvc.perform(post("/api/v1/purchase-products/"+f.sku+"/force-delete").with(admin()).with(csrf()).contentType("application/json").content(input(f,2,f.sku))).andExpect(status().isNotFound());
        assertEquals(1,db.queryForObject("select count(*) from audit_log where action='purchase.force-delete' and resource_id=?",Integer.class,f.sku));
    }
    @Test void rejectsWrongRoleCsrfConfirmationAndStaleVersionWithoutChangingAnything() throws Exception {
        var f=fixture();var before=preserved(f);
        for(var role:List.of("PURCHASE","FINANCE","EMPLOYEE")) {
            mvc.perform(post("/api/v1/purchase-products/"+f.sku+"/force-delete").with(user(role).authorities(new SimpleGrantedAuthority("ROLE_"+role),new SimpleGrantedAuthority("PERM_purchase")))
                    .with(csrf()).contentType("application/json").content(input(f,2,f.sku))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/purchase-products/"+f.sku+"/force-delete").with(admin()).contentType("application/json").content(input(f,2,f.sku))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/purchase-products/"+f.sku+"/force-delete").with(admin()).with(csrf()).contentType("application/json").content(input(f,1,f.sku))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/purchase-products/"+f.sku+"/force-delete").with(admin()).with(csrf()).contentType("application/json").content(input(f,2,"OTHER-SKU"))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/purchase-products/"+f.sku+"/force-delete").with(admin()).with(csrf()).contentType("application/json").content("{}" )).andExpect(status().isUnprocessableEntity());
        assertEquals(before,preserved(f));assertTrue(products.exists(f.sku));
        assertEquals(0,db.queryForObject("select count(*) from audit_log where action='purchase.force-delete' and resource_id=?",Integer.class,f.sku));
    }
    @Test void blocksRunningImportButNeverRollsBackOtherProducts() throws Exception {
        var f=fixture();db.update("update import_job set status='importing' where id=?",f.job);
        var before=preserved(f);
        mvc.perform(post("/api/v1/purchase-products/"+f.sku+"/force-delete").with(admin()).with(csrf()).contentType("application/json").content(input(f,2,f.sku))).andExpect(status().isConflict());
        assertEquals(before,preserved(f));assertTrue(products.exists(f.sku));
    }
    @Test void oldQuoteWithoutImageCanStillUseArchivedImageAndAuditPinsIt() throws Exception {
        var f=fixture();db.update("update quotation_record set payload=payload-'productImage' where id=?",f.quote);
        var before=preserved(f);
        mvc.perform(post("/api/v1/purchase-products/"+f.sku+"/force-delete").with(admin()).with(csrf()).contentType("application/json").content(input(f,2,f.sku))).andExpect(status().isOk());
        assertEquals(before,preserved(f));
        db.update("update asset_object set storage_state='temporary',expires_at=now()-interval '1 day' where id=?",f.image);
        assertTrue(assets.findExpiredUnreferenced(Instant.now()).stream().noneMatch(a->a.id.equals(f.image)));
        mvc.perform(get("/api/v1/purchase-products/deleted-snapshots").param("skus",f.sku).with(user("EMPLOYEE").authorities(new SimpleGrantedAuthority("PERM_myRecords"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].productImage").value("/api/v1/assets/"+f.image));
    }
    @Test void databaseFailureRollsBackDeletionAndItsSuccessAuditTogether() throws Exception {
        var f=fixture();var before=preserved(f);
        db.execute("create table force_delete_test_reference(product_id uuid references purchase_product(id))");
        try {
            db.update("insert into force_delete_test_reference values(?)",f.product);
            var response=mvc.perform(post("/api/v1/purchase-products/"+f.sku+"/force-delete").with(admin()).with(csrf()).contentType("application/json").content(input(f,2,f.sku))).andReturn().getResponse();
            assertTrue(response.getStatus()>=400);
            assertTrue(products.exists(f.sku));assertEquals(before,preserved(f));
            assertEquals(0,db.queryForObject("select count(*) from audit_log where action='purchase.force-delete' and resource_id=?",Integer.class,f.sku));
            assertEquals(1,db.queryForObject("select count(*) from purchase_product_image where product_id=?",Integer.class,f.product));
        } finally { db.execute("drop table force_delete_test_reference"); }
    }
}
