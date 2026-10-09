package com.milano.quotation.fob;

import com.milano.quotation.common.AppException;
import com.milano.quotation.purchase.PurchaseProductRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
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
@WithMockUser(username="FOBBUYER", roles="SUPER_ADMIN")
class FobPurchasePostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", postgres::getUsername); r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.sql.init.mode", () -> "never"); r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.jpa.defer-datasource-initialization", () -> "false"); r.add("spring.flyway.enabled", () -> "true");
    }
    @Autowired FobPurchaseService service;
    @Autowired FobPurchaseRepository repository;
    @Autowired PurchaseProductRepository ordinary;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    MockMvc mvc;
    @BeforeEach void setup() { mvc = webAppContextSetup(context).apply(springSecurity()).build(); }
    String sku() { return "FB-" + UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT); }
    void catalogPair(String sku) {
        service.confirm(confirmation(List.of(row(sku))));
        jdbc.sql("""
            insert into purchase_product(id,sku,payload,version,created_at,updated_at,catalog_state,quote_ready)
            values(:id,:sku,cast(:payload as jsonb),3,now(),now(),'ready',true)
            """).param("id",UUID.randomUUID()).param("sku",sku)
                .param("payload","{\"sku\":\""+sku+"\",\"dataSource\":\"legacy_2026\",\"purchasePriceCny\":21.5,\"weightG\":80,\"singleFreightCny\":4}")
                .update();
    }
    JsonNode catalog(String query,int page,int size) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/purchase-catalog").param("q",query).param("page",String.valueOf(page)).param("size",String.valueOf(size)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void combinedCatalogKeepsBothSourcesAllTiersAndStablePaginationWithoutWrites() throws Exception {
        String sku=sku();catalogPair(sku);
        var before=service.get(sku);long normalCount=ordinary.count(),fobCount=repository.count();
        var page=catalog(" "+sku.toLowerCase(Locale.ROOT)+" ",0,10);
        assertEquals(2,page.path("total").asInt());assertEquals(2,page.path("items").size());
        var records=new HashMap<String,JsonNode>();for(var item:page.path("items")) records.put(item.path("dataSource").asText(),item);
        assertEquals(Set.of("legacy_2026","fob"),records.keySet());
        assertEquals(21.5,records.get("legacy_2026").path("purchasePriceCny").asDouble());
        assertEquals(5,records.get("fob").path("parsed").path("priceTiers").size());
        assertEquals(3,records.get("legacy_2026").path("_version").asInt());
        var first=catalog(sku,0,1);var second=catalog(sku,1,1);var empty=catalog(sku,2,1);
        assertEquals(2,first.path("totalPages").asInt());assertEquals(2,empty.path("total").asInt());assertTrue(empty.path("items").isEmpty());
        assertNotEquals(first.path("items").get(0).path("dataSource"),second.path("items").get(0).path("dataSource"));
        assertEquals(before,service.get(sku));assertEquals(normalCount,ordinary.count());assertEquals(fobCount,repository.count());
    }
    @Test void combinedCatalogFindsFobOnlyRowsAndMatchesBothStoresOnPartialSearch() throws Exception {
        String sku=sku();catalogPair(sku);String only=sku+"-ONLY";service.confirm(confirmation(List.of(row(only))));
        assertEquals(1,catalog(only,0,10).path("total").asInt());
        assertEquals("fob",catalog(only,0,10).path("items").get(0).path("dataSource").asText());
        assertEquals(2,catalog(sku,0,10).path("total").asInt(),"Exact SKU takes precedence over prefixed SKUs");
        assertEquals(3,catalog(sku.substring(0,sku.length()-1),0,10).path("total").asInt());
        assertEquals(0,catalog(sku+"-MISSING",0,10).path("total").asInt());
        assertTrue(catalog("",0,10).path("total").asLong()>=3);
    }
    @Test void combinedCatalogCannotBypassFobOrProcurementReadPermissions() throws Exception {
        String sku=sku();catalogPair(sku);
        mvc.perform(get("/api/v1/purchase-catalog").param("q",sku).with(user("buyer").authorities(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_PURCHASE"),new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_purchase"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/v1/purchase-catalog").param("q",sku).with(user("custom").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_purchase"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.items[0].dataSource").value("legacy_2026"));
        mvc.perform(get("/api/v1/purchase-catalog").with(user("employee").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_quote"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/purchase-catalog").with(anonymous())).andExpect(status().isUnauthorized());
    }
    ObjectNode row(String sku) { return mapper.createObjectNode().put("sku",sku).put("weightRaw","80").put("moqRaw","1")
            .put("priceRaw","单价16;100起单价13.8;300单价13;500单价13;1000单价12.8").put("freightRaw","100件30;200件40").put("notes","保留备注"); }
    FobPurchaseService.Confirmation confirmation(List<JsonNode> rows) {
        var p = service.preview(rows); return new FobPurchaseService.Confirmation(rows, p.rows().stream().map(FobPurchaseService.PreviewRow::expected).toList(),p.digest());
    }
    @Test void privateWorkbookCorpusPreviewKeepsSourceConflictsAndNeverWritesRecords() throws Exception {
        String source = System.getenv("FOB_CORPUS_PATH");
        Assumptions.assumeTrue(source != null && !source.isBlank(), "Private source workbook is not stored in Git");
        var input = mapper.readTree(java.nio.file.Files.readString(java.nio.file.Path.of(source)));
        assertEquals(317, input.size());
        long before = repository.count(), ordinaryBefore = ordinary.count();
        var report = mapper.createArrayNode(); int ready = 0;
        for (int start = 0; start < input.size(); start += 100) {
            var batch = new ArrayList<JsonNode>();
            for (int i = start; i < Math.min(input.size(), start + 100); i++) {
                var row = (ObjectNode) input.get(i).deepCopy(); row.remove("sourceRow"); batch.add(row);
            }
            var preview = service.preview(batch);
            for (var row : preview.rows()) {
                var item = report.addObject().put("sku", row.sku()).put("sourceRow", input.get(start + row.sourceRow() - 1).path("sourceRow").asInt());
                item.set("issues", mapper.valueToTree(row.issues())); item.set("notices", mapper.valueToTree(row.notices()));
                if (row.issues().isEmpty()) ready++;
            }
        }
        assertEquals(290, ready);
        assertEquals(before, repository.count()); assertEquals(ordinaryBefore, ordinary.count());
        assertTrue(report.valueStream().anyMatch(row -> row.path("sku").asText().equals("PF2600270") && row.path("issues").toString().contains("同批SKU")));
        java.nio.file.Files.writeString(java.nio.file.Path.of(source).resolveSibling("backend-preview.json"), mapper.writeValueAsString(report));
    }
    @Test void batchCreateUpdateNoopAndSparsePreservationWithFullTierReplacementAndAudit() {
        String a = sku(), b = sku(); long ordinaryBefore = ordinary.count();
        List<JsonNode> rows = List.of(row(a),row(b)); var p = service.preview(rows);
        assertTrue(p.canSave()); assertFalse(repository.existsById(a));
        assertEquals("fob", p.rows().getFirst().effective().path("dataSource").asText());
        assertEquals("pending", p.rows().getFirst().effective().path("verificationStatus").asText());
        assertEquals(2,service.confirm(confirmation(rows)).added());
        assertEquals("fob",repository.findById(a).orElseThrow().payload.path("dataSource").asText());
        assertEquals(5,service.get(a).path("parsed").path("priceTiers").size());
        long version = repository.findById(a).orElseThrow().version;
        assertEquals(2,service.confirm(confirmation(rows)).unchanged());
        assertEquals(version,repository.findById(a).orElseThrow().version); assertEquals(1,service.history(a).size());
        var patch = mapper.createObjectNode().put("sku",a).put("priceRaw","单价15;100件13;300件12;1000件11").put("notes","").put("freightRaw","0");
        assertEquals(1,service.confirm(confirmation(List.of(patch))).updated());
        var saved = service.get(a); assertEquals(4,saved.path("parsed").path("priceTiers").size());
        assertEquals("fob",saved.path("dataSource").asText());
        assertEquals("保留备注",saved.path("notes").asText()); assertEquals(0,saved.path("parsed").path("freight").path("unitFreightCny").asDouble());
        assertEquals(2,service.history(a).size()); assertEquals("FOBBUYER",service.history(a).getFirst().actorAccount());
        assertEquals(ordinaryBefore,ordinary.count()); assertTrue(ordinary.findBySku(a).isEmpty());
    }
    @Test void stalePreviewBlocksWholeBatchIncludingOtherwiseNewRows() {
        String a = sku(), b = sku(); service.confirm(confirmation(List.of(row(a))));
        List<JsonNode> rows = List.of(row(a).put("notes","本批次"),row(b)); var stale = confirmation(rows);
        service.confirm(confirmation(List.of(mapper.createObjectNode().put("sku",a).put("notes","其他人修改"))));
        assertThrows(AppException.class,()->service.confirm(stale)); assertFalse(repository.existsById(b));
        assertEquals("其他人修改",service.get(a).path("notes").asText());
    }
    @Test void invalidRowOrTamperedPreviewDoesNotPartiallyPersist() {
        String a = sku(), b = sku(); List<JsonNode> rows = List.of(row(a),row(b).put("priceRaw","单价27;批发价24"));
        assertFalse(service.preview(rows).canSave());
        assertFalse(service.preview(List.of(row(sku()).put("notes", "版费300元，满3000个可退"))).canSave());
        assertThrows(AppException.class,()->service.confirm(confirmation(rows))); assertFalse(repository.existsById(a));
        List<JsonNode> good = List.of(row(a)); var approved = confirmation(good);
        var changed = new FobPurchaseService.Confirmation(List.of(row(a).put("priceRaw","25")),approved.expected(),approved.digest());
        assertThrows(AppException.class,()->service.confirm(changed)); assertFalse(repository.existsById(a));
    }
    @Test void identicalDuplicatesAreSkippedButConflictsBlockTheEntireBatch() {
        String conflict = sku(), other = sku();
        List<JsonNode> conflicting = List.of(row(conflict), row(conflict).put("priceRaw", "999"), row(other));
        var preview = service.preview(conflicting);
        assertFalse(preview.canSave()); assertTrue(preview.rows().getFirst().issues().getFirst().contains("同批SKU"));
        assertThrows(AppException.class, () -> service.confirm(confirmation(conflicting)));
        assertFalse(repository.existsById(conflict)); assertFalse(repository.existsById(other));
        assertTrue(service.history(conflict).isEmpty());
        String a = sku(); List<JsonNode> rows = List.of(row(a),row(a));
        var result = service.confirm(confirmation(rows)); assertEquals(1,result.added()); assertEquals(1,result.skipped());
        assertEquals(16,service.get(a).path("parsed").path("priceTiers").get(0).path("unitPriceCny").asInt());
        assertThrows(AppException.class,()->service.preview(Collections.nCopies(101,row(sku()))));
        assertThrows(AppException.class,()->service.preview(List.of(row(sku()).put("version",999))));
        assertThrows(AppException.class,()->service.preview(List.of(row(sku()).put("dataSource","standard"))));
    }
    @Test void quotePermissionAllowsReadButOnlyMaintainersCanWriteOrViewHistoryAndCsrfIsRequired() throws Exception {
        String sku = sku(); String body = mapper.writeValueAsString(List.of(row(sku)));
        mvc.perform(get("/api/v1/fob-purchase-products/"+sku)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FOB_PURCHASE_NOT_FOUND"));
        service.confirm(confirmation(List.of(row(sku))));
        for (String role : List.of("EMPLOYEE", "FINANCE", "LOGISTICS")) {
            var nonAdmin = user("OTHER").authorities(()->"ROLE_"+role,()->"PERM_purchase",()->"PERM_quote",()->"PERM_allRecords");
            mvc.perform(get("/api/v1/fob-purchase-products/"+sku).with(nonAdmin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.parsed.priceTiers.length()").value(5));
            mvc.perform(get("/api/v1/fob-purchase-products/"+sku).with(user("NOQUOTE").authorities(()->"ROLE_"+role,()->"PERM_purchase",()->"PERM_allRecords"))).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/fob-purchase-products/"+sku+"/history").with(nonAdmin)).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/fob-purchase-products/paste/preview").with(csrf()).with(nonAdmin).contentType("application/json").content(body)).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/fob-purchase-products/paste/confirm").with(csrf()).with(nonAdmin).contentType("application/json").content(mapper.writeValueAsString(confirmation(List.of(row(sku)))))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/fob-purchase-products/paste/preview").with(csrf()).with(user("SALES").authorities(()->"PERM_quote")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/fob-purchase-products/paste/preview").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/fob-purchase-products/paste/preview").with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.canSave").value(true));
        mvc.perform(get("/api/v1/fob-purchase-products/"+sku)).andExpect(status().isOk()).andExpect(jsonPath("$.data.parsed.priceTiers.length()").value(5));
        mvc.perform(get("/api/v1/fob-purchase-products/"+sku+"/history").with(user("SALES").authorities(()->"PERM_quote"))).andExpect(status().isForbidden());
    }
    @Test void reversedConcurrentNewBatchesHaveOneWinnerAndOneRecoverableConflict() throws Exception {
        String a=sku(), b=sku();
        var first=mapper.writeValueAsString(confirmation(List.of(row(a),row(b))));
        var second=mapper.writeValueAsString(confirmation(List.of(row(b),row(a))));
        // Isolated PostgreSQL fault fixture: overlap the two insert transactions deterministically.
        jdbc.sql("create function fob_slow_insert() returns trigger language plpgsql as $$ begin perform pg_sleep(0.2); return NEW; end $$").update();
        jdbc.sql("create trigger fob_slow_insert before insert on fob_purchase_product for each row execute function fob_slow_insert()").update();
        try (var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var tasks=new ArrayList<java.util.concurrent.Future<Integer>>();
            for(String body:List.of(first,second)) tasks.add(pool.submit(()->{
                start.await();
                return mvc.perform(post("/api/v1/fob-purchase-products/paste/confirm").with(csrf())
                    .with(user("FOBRACE").roles("SUPER_ADMIN")).contentType("application/json").content(body))
                    .andReturn().getResponse().getStatus();
            }));
            start.countDown();
            var statuses=new ArrayList<Integer>();for(var task:tasks) statuses.add(task.get(20,java.util.concurrent.TimeUnit.SECONDS));
            Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
            assertEquals(1,service.history(a).size());assertEquals(1,service.history(b).size());
            assertEquals(2,service.confirm(confirmation(List.of(row(a),row(b)))).unchanged());
        } finally {
            jdbc.sql("drop trigger fob_slow_insert on fob_purchase_product").update();
            jdbc.sql("drop function fob_slow_insert()").update();
        }
    }
    @Test void concurrentUpdatesOfOnePreviewCannotLoseTheWinningPriceOrPartiallyCreateCompanionRows() throws Exception {
        String sku=sku();service.confirm(confirmation(List.of(row(sku))));
        var bodies=new ArrayList<String>();var companions=new ArrayList<String>();
        for(int i=0;i<8;i++) {
            String companion=sku();companions.add(companion);
            bodies.add(mapper.writeValueAsString(confirmation(List.of(row(sku).put("priceRaw",String.valueOf(20+i)),row(companion)))));
        }
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var start=new java.util.concurrent.CountDownLatch(1);var tasks=new ArrayList<java.util.concurrent.Future<Integer>>();
            for(String body:bodies)tasks.add(pool.submit(()->{start.await();return mvc.perform(post("/api/v1/fob-purchase-products/paste/confirm")
                .with(csrf()).with(user("FOBUPDATE").roles("SUPER_ADMIN")).contentType("application/json").content(body)).andReturn().getResponse().getStatus();}));
            start.countDown();int winner=-1;
            for(int i=0;i<tasks.size();i++) {int status=tasks.get(i).get(20,java.util.concurrent.TimeUnit.SECONDS);
                if(status==200){assertEquals(-1,winner);winner=i;}else assertEquals(409,status);
            }
            assertTrue(winner>=0);assertEquals(20+winner,service.get(sku).path("parsed").path("priceTiers").get(0).path("unitPriceCny").asInt());
            for(int i=0;i<companions.size();i++)assertEquals(i==winner,repository.existsById(companions.get(i)));
            assertEquals(2,service.history(sku).size());
        }
    }
}
