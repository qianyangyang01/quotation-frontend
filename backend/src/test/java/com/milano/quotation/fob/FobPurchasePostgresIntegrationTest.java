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
    MockMvc mvc;
    @BeforeEach void setup() { mvc = webAppContextSetup(context).apply(springSecurity()).build(); }
    String sku() { return "FB-" + UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT); }
    ObjectNode row(String sku) { return mapper.createObjectNode().put("sku",sku).put("weightRaw","80").put("moqRaw","1")
            .put("priceRaw","单价16;100起单价13.8;300单价13;500单价13;1000单价12.8").put("freightRaw","100件30;200件40").put("notes","保留备注"); }
    FobPurchaseService.Confirmation confirmation(List<JsonNode> rows) {
        var p = service.preview(rows); return new FobPurchaseService.Confirmation(rows, p.rows().stream().map(FobPurchaseService.PreviewRow::expected).toList(),p.digest());
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
    @Test void sameBatchDuplicatesKeepFirstAndBatchLimitIsEnforced() {
        String a = sku(); List<JsonNode> rows = List.of(row(a),row(a).put("priceRaw","999"));
        var result = service.confirm(confirmation(rows)); assertEquals(1,result.added()); assertEquals(1,result.skipped());
        assertEquals(16,service.get(a).path("parsed").path("priceTiers").get(0).path("unitPriceCny").asInt());
        assertThrows(AppException.class,()->service.preview(Collections.nCopies(101,row(sku()))));
        assertThrows(AppException.class,()->service.preview(List.of(row(sku()).put("version",999))));
        assertThrows(AppException.class,()->service.preview(List.of(row(sku()).put("dataSource","standard"))));
    }
    @Test void quotePermissionAllowsReadButOnlySuperAdminCanWriteOrViewHistoryAndCsrfIsRequired() throws Exception {
        String sku = sku(); String body = mapper.writeValueAsString(List.of(row(sku)));
        mvc.perform(get("/api/v1/fob-purchase-products/"+sku)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FOB_PURCHASE_NOT_FOUND"));
        service.confirm(confirmation(List.of(row(sku))));
        for (String role : List.of("EMPLOYEE", "FINANCE", "PURCHASE", "LOGISTICS")) {
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
}
