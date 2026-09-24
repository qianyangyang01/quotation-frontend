package com.milano.quotation.purchase;

import com.milano.quotation.common.AppException;
import com.milano.quotation.security.UserAccount;
import com.milano.quotation.security.UserAccountRepository;
import com.milano.quotation.security.UserAccountService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.*;
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
@WithMockUser(username="PASTEBUYER", authorities="PERM_purchase")
class PurchasePastePostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", postgres::getUsername); r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.sql.init.mode", () -> "never"); r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.jpa.defer-datasource-initialization", () -> "false"); r.add("spring.flyway.enabled", () -> "true");
    }
    @Autowired PurchasePasteService paste;
    @Autowired PurchaseProductService products;
    @Autowired PurchaseProductRepository repository;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebApplicationContext context;
    @Autowired UserAccountRepository users;
    @Autowired UserAccountService accounts;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    @BeforeEach void setup() { mvc = webAppContextSetup(context).apply(springSecurity()).build(); }
    String sku() { return "PA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT); }
    ObjectNode full(String sku) { return mapper.createObjectNode().put("sku", sku).put("weightG", 100).put("minOrderQty", 1).put("purchasePriceCny", 12).put("singleFreightCny", 5).put("freight10Cny", 10).put("taxPoint", 0.08); }
    ObjectNode patch(String sku, int price) { return mapper.createObjectNode().put("sku", sku).put("purchasePriceCny", price); }
    PurchasePasteService.Confirmation confirmation(List<JsonNode> rows) {
        return new PurchasePasteService.Confirmation(rows, paste.preview(rows).rows().stream().map(PurchasePasteService.PreviewRow::expected).toList());
    }
    long historyCount(String sku) { return products.history(sku, PageRequest.of(0, 20)).getTotalElements(); }

    @Test void sparseUpdatePreservesIdentityImagesSourceAndCatalogAndUsesServerActor() throws Exception {
        var sku = sku();
        var initial = (ObjectNode) products.upsert(full(sku).put("notes", "旧备注").put("productImage", "/old.png").put("sourceSheet", "原表").put("sourceRow", 88));
        products.changeCatalogState(sku, "disabled", initial.path("_version").asLong());
        var original = repository.findBySku(sku).orElseThrow();
        users.saveAndFlush(UserAccount.create("PASTEBUYER", "采购测试员", "unused-test-hash", "purchase", false));
        var principal = accounts.loadUserByUsername("PASTEBUYER");
        var auth = new UsernamePasswordAuthenticationToken(principal, "", principal.getAuthorities());
        List<JsonNode> rows = List.of(patch(sku, 15).put("notes", " ").put("taxPoint", 0).put("sourceRow", 3));
        var preview = paste.preview(rows);
        assertEquals("update", preview.rows().getFirst().action());
        assertEquals(2, preview.rows().getFirst().changes().size());
        mvc.perform(post("/api/v1/purchase-products/paste/confirm").with(csrf()).with(authentication(auth))
                .contentType("application/json").content(mapper.writeValueAsString(confirmation(rows))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.updated[0].purchasePriceCny").value(15));
        var after = products.get(sku);
        assertEquals("旧备注", after.path("notes").asText());
        assertEquals("/old.png", after.path("productImage").asText());
        assertEquals("原表", after.path("sourceSheet").asText());
        assertEquals(88, after.path("sourceRow").asInt());
        assertEquals("disabled", after.path("catalogState").asText());
        assertEquals(original.id, repository.findBySku(sku).orElseThrow().id);
        var history = products.history(sku, PageRequest.of(0, 1)).getContent().getFirst();
        assertEquals("采购粘贴更新", history.operation());
        assertEquals("PASTEBUYER", history.actorAccount());
        assertEquals("采购测试员", history.actorName());
        assertNotNull(history.createdAt());
        assertEquals(12, history.changes().get(0).path("before").asInt());
        assertEquals(15, history.changes().get(0).path("after").asInt());
        assertEquals(2, history.changes().size());
    }

    @Test void mixedBatchDeduplicatesNormalizesSkuAndNoopDoesNotWriteAgain() {
        var old = sku(); var added = sku();
        products.upsert(full(old));
        List<JsonNode> rows = List.of(patch(" " + old.toLowerCase(Locale.ROOT) + " ", 0), full(added), patch(old, 999));
        var result = paste.confirm(confirmation(rows));
        assertEquals(1, result.added().size()); assertEquals(1, result.updated().size());
        assertEquals(3, result.skipped().getFirst().sourceRow());
        var before = products.get(old);
        var again = paste.confirm(confirmation(rows));
        assertEquals(2, again.unchanged().size());
        assertEquals(0, again.updated().size());
        assertEquals(before, products.get(old));
        assertEquals(2, historyCount(old));
    }

    @Test void mergedGroupsValidateAndNewRowsNeedRequiredFieldsWithoutPartialWrites() {
        var old = sku(); products.upsert(full(old).put("tier2MinQty", 10).put("tier2PriceCny", 8));
        var tierPatch = mapper.createObjectNode().put("sku", old).put("tier2PriceCny", 7);
        paste.confirm(confirmation(List.of(tierPatch)));
        assertEquals(10, products.get(old).path("tier2MinQty").asInt());
        var added = sku();
        assertThrows(AppException.class, () -> paste.preview(List.of(full(added), patch(old, 5).put("minOrderQty", 20))));
        assertThrows(AppException.class, () -> paste.preview(List.of(patch(added, 5))));
        assertThrows(AppException.class, () -> paste.preview(List.of(full(added).putNull("taxPoint"))));
        assertFalse(products.exists(added));
        assertEquals(12, products.get(old).path("purchasePriceCny").asInt());
        assertEquals(2, historyCount(old));
    }

    @Test void legacyPreviewUsesSameFinalPriceAsSaveWithoutChangingSource() {
        var sku = sku(); var initial = full(sku).put("dataSource", "legacy_2026").put("taxIncludedPriceCny", 14);
        initial.remove("freight10Cny");
        products.upsert(initial);
        List<JsonNode> rows = List.of(patch(sku, 18));
        var unchanged = paste.preview(rows).rows().getFirst();
        assertEquals("unchanged", unchanged.action());
        assertFalse(unchanged.notices().isEmpty());
        rows = List.of(patch(sku, 18).put("taxIncludedPriceCny", 16));
        var preview = paste.preview(rows).rows().getFirst();
        assertEquals(16, preview.effective().path("purchasePriceCny").asInt());
        var saved = paste.confirm(confirmation(rows)).updated().getFirst();
        assertEquals(preview.effective().path("purchasePriceCny"), saved.path("purchasePriceCny"));
        assertEquals("legacy_2026", saved.path("dataSource").asText());
        assertThrows(AppException.class, () -> paste.preview(List.of(patch(sku, -2))));
    }

    @Test void staleUpdateNewSkuRaceAndDeletedRecreatedIdentityRejectWholeBatch() {
        var sku = sku(); var newSku = sku(); products.upsert(full(sku));
        var pending = confirmation(List.of(full(newSku), patch(sku, 15)));
        products.update(sku, ((ObjectNode) products.get(sku)).put("color", "蓝色"));
        assertThrows(AppException.class, () -> paste.confirm(pending));
        assertFalse(products.exists(newSku)); assertEquals(12, products.get(sku).path("purchasePriceCny").asInt());
        var addPending = confirmation(List.of(full(newSku)));
        products.upsert(full(newSku));
        assertThrows(AppException.class, () -> paste.confirm(addPending));
        var recreatePending = confirmation(List.of(patch(sku, 15)));
        repository.deleteById(repository.findBySku(sku).orElseThrow().id);
        assertThrows(AppException.class, () -> paste.confirm(recreatePending));
        products.upsert(full(sku));
        assertThrows(AppException.class, () -> paste.confirm(recreatePending));
    }

    @Test void transactionRollbackUndoesBothProductsAndHistory() {
        var sku = sku(); products.upsert(full(sku));
        var added = sku(); var pending = confirmation(List.of(patch(sku, 15), full(added)));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            paste.confirm(pending); status.setRollbackOnly();
        });
        assertEquals(12, products.get(sku).path("purchasePriceCny").asInt());
        assertEquals(1, historyCount(sku)); assertFalse(products.exists(added));
    }

    @Test void concurrentConfirmHasExactlyOneWinnerAndOneModificationRecord() throws Exception {
        var sku = sku(); products.upsert(full(sku));
        var pending = confirmation(List.of(patch(sku, 15)));
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> { gate.await(); try { paste.confirm(pending); return true; } catch (AppException e) { return false; } };
            var a = pool.submit(attempt); var b = pool.submit(attempt); gate.countDown();
            assertNotEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(15, products.get(sku).path("purchasePriceCny").asInt());
        assertEquals(2, historyCount(sku));
    }

    @Test void protectedFieldsLimitsAndPermissionAreEnforcedAndOldEndpointStillSkips() throws Exception {
        var sku = sku(); products.upsert(full(sku));
        for (var field : List.of("productImage", "catalogState", "dataSource", "_version", "actorAccount"))
            assertThrows(AppException.class, () -> paste.preview(List.of(patch(sku, 15).put(field, "forged"))));
        assertThrows(AppException.class, () -> paste.preview(List.of()));
        assertThrows(AppException.class, () -> paste.preview(Collections.nCopies(101, full(sku))));
        assertEquals(100, paste.preview(java.util.stream.IntStream.range(0,100).mapToObj(i -> (JsonNode)full(sku())).toList()).rows().size());
        mvc.perform(post("/api/v1/purchase-products/paste/preview").with(csrf()).with(user("SALES").authorities(() -> "PERM_quote"))
                .contentType("application/json").content("[]")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/purchase-products/paste/confirm").with(csrf()).with(user("SALES").authorities(() -> "PERM_quote"))
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/purchase-products/paste/preview").with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(List.of(patch(sku,15)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].expected.productId").isNotEmpty());
        assertTrue(products.createPasted(List.of(full(sku).put("purchasePriceCny", 88))).isEmpty());
        assertEquals(12, products.get(sku).path("purchasePriceCny").asInt());
    }

    @Test void savedQuotationSnapshotRemainsByteForByteUnchanged() {
        var sku = sku(); products.upsert(full(sku));
        var id = UUID.randomUUID();
        var payload = mapper.createObjectNode().put("primarySku", sku).put("systemQuote", 30).put("customerQuote", 35)
                .put("freight", 10).put("financeReviewStatus", "approved").put("dealStatus", "won");
        jdbc.update("insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at,lifecycle_state) values(?,?,?,'processed',cast(? as jsonb),3,now(),now(),'active')",
                id, "PASTE-" + id.toString().substring(0,8), "PASTEBUYER", payload.toString());
        var before = jdbc.queryForMap("select * from quotation_record where id=?", id);
        paste.confirm(confirmation(List.of(patch(sku, 19))));
        assertEquals(before, jdbc.queryForMap("select * from quotation_record where id=?", id));
        assertEquals(19, products.get(sku).path("purchasePriceCny").asInt(), "new queries see the new price");
    }
}
