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

    @Test void legacyEditorAndSparseUpdatesShareAutomaticPricingAndAudit() throws Exception {
        var sku = sku();
        var original = products.upsert(full(sku).put("dataSource", "legacy_2026")
                .put("purchasePriceBasis", "tax_included").put("sourceQuotedPriceCny", 110)
                .put("purchasePriceCny", 121).put("taxIncludedPriceCny", 121).put("taxPoint", .1));
        var edited = (ObjectNode) original.deepCopy();
        edited.put("legacyPriceMode", "auto_tax").put("sourceQuotedPriceCny", 100);
        mvc.perform(post("/api/v1/purchase-products/" + sku + "/maintenance").with(csrf())
                .contentType("application/json").content(mapper.writeValueAsString(edited)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.purchasePriceCny").value(110))
                .andExpect(jsonPath("$.data.sourceQuotedPriceCny").value(100));
        assertEquals(110, products.get(sku).path("taxIncludedPriceCny").asDouble());
        var audit = products.history(sku, PageRequest.of(0, 1)).getContent().getFirst();
        assertTrue(audit.changes().toString().contains("sourceQuotedPriceCny"));
        assertTrue(audit.changes().toString().contains("taxIncludedPriceCny"));

        List<JsonNode> patch = List.of(patch(sku, 80).put("taxPoint", .08).put("taxIncludedPriceCny", 999));
        var preview = paste.preview(patch).rows().getFirst();
        assertEquals(86.4, preview.effective().path("purchasePriceCny").asDouble());
        assertTrue(preview.notices().getFirst().contains("未税采购单价"));
        paste.confirm(confirmation(patch));
        assertEquals(80, products.get(sku).path("sourceQuotedPriceCny").asDouble());
        assertEquals(86.4, products.get(sku).path("taxIncludedPriceCny").asDouble());
        assertEquals(1, paste.confirm(confirmation(patch)).unchanged().size());
        assertThrows(AppException.class, () -> paste.preview(List.of(mapper.createObjectNode().put("sku", sku).put("taxIncludedPriceCny", 123))));
        var rateOnly = List.<JsonNode>of(mapper.createObjectNode().put("sku", sku).put("taxPoint", 0));
        paste.confirm(confirmation(rateOnly));
        assertEquals(80, products.get(sku).path("purchasePriceCny").asDouble());

        // No-change reads and non-pricing edits retain the established amount.
        var unrelated = (ObjectNode) products.get(sku).deepCopy();
        unrelated.put("notes", "只改备注");
        products.update(sku, unrelated);
        assertEquals(80, products.get(sku).path("purchasePriceCny").asDouble());
        assertEquals(409, assertThrows(AppException.class, () -> products.update(sku, unrelated)).status().value());
    }

    @Test void autoTaxMaintenanceRejectsReplayPermissionLossCsrfAndInvalidInputsWithoutWrites() throws Exception {
        var sku = sku();
        var original = products.upsert(full(sku).put("dataSource", "legacy_2026").put("legacyPriceMode", "auto_tax")
                .put("sourceQuotedPriceCny", 110).put("taxPoint", .1));
        var edit = (ObjectNode) original.deepCopy();
        edit.put("sourceQuotedPriceCny", 100).put("taxIncludedPriceCny", 999);
        var endpoint = "/api/v1/purchase-products/" + sku + "/maintenance";
        mvc.perform(post(endpoint).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(edit)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.purchasePriceCny").value(110));
        var saved = products.get(sku);
        var auditCount = historyCount(sku);
        mvc.perform(post(endpoint).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(edit)))
                .andExpect(status().isConflict());
        mvc.perform(post(endpoint).contentType("application/json").content(mapper.writeValueAsString(saved)))
                .andExpect(status().isForbidden());
        mvc.perform(post(endpoint).with(csrf()).with(user("SALES").authorities(() -> "PERM_quote"))
                .contentType("application/json").content(mapper.writeValueAsString(saved))).andExpect(status().isForbidden());
        var oldClient = (ObjectNode) saved.deepCopy(); oldClient.remove("legacyPriceMode");
        mvc.perform(post(endpoint).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(oldClient)))
                .andExpect(status().isConflict());
        for (var invalid : List.of(((ObjectNode)saved.deepCopy()).putNull("sourceQuotedPriceCny"),
                ((ObjectNode)saved.deepCopy()).put("sourceQuotedPriceCny", -1),
                ((ObjectNode)saved.deepCopy()).put("sourceQuotedPriceCny", true),
                ((ObjectNode)saved.deepCopy()).putNull("taxPoint"),
                ((ObjectNode)saved.deepCopy()).put("taxPoint", 10),
                ((ObjectNode)saved.deepCopy()).put("taxPoint", "0.1"))) {
            mvc.perform(post(endpoint).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(invalid)))
                    .andExpect(status().isUnprocessableEntity());
        }
        assertEquals(saved, products.get(sku));
        assertEquals(auditCount, historyCount(sku));
    }

    @Test void twoAutoTaxEditorsHaveOneWinnerAndCannotAlterSavedQuotationSnapshot() throws Exception {
        var sku = sku();
        var original = products.upsert(full(sku).put("dataSource", "legacy_2026").put("legacyPriceMode", "auto_tax")
                .put("sourceQuotedPriceCny", 110).put("taxPoint", .1));
        var id = UUID.randomUUID();
        var payload = mapper.createObjectNode().put("primarySku",sku).put("purchaseUnitPriceCny",121)
                .put("systemQuote",200).put("customerQuote",210).put("freight",10)
                .put("financeReviewStatus","approved").put("dealStatus","won");
        jdbc.update("insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at,lifecycle_state) values(?,?,?,'processed',cast(? as jsonb),3,now(),now(),'active')",
                id,"AUTO-QA-"+id.toString().substring(0,8),"PASTEBUYER",payload.toString());
        var history = jdbc.queryForMap("select * from quotation_record where id=?",id);
        var gate = new CountDownLatch(1);
        var edits = List.of(((ObjectNode)original.deepCopy()).put("sourceQuotedPriceCny",100),
                ((ObjectNode)original.deepCopy()).put("sourceQuotedPriceCny",90));
        long initialAudit = historyCount(sku);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var attempts = edits.stream().map(edit -> pool.submit(() -> {
                gate.await();
                try { products.update(sku,edit); return true; }
                catch (AppException conflict) { assertEquals(409,conflict.status().value()); return false; }
            })).toList();
            gate.countDown();
            assertNotEquals(attempts.get(0).get(20,TimeUnit.SECONDS),attempts.get(1).get(20,TimeUnit.SECONDS));
        }
        assertEquals(initialAudit+1,historyCount(sku));
        var winner=products.get(sku);
        double expected=winner.path("sourceQuotedPriceCny").asDouble()==100?110:99;
        assertEquals(expected,winner.path("purchasePriceCny").asDouble());
        for(int i=0;i<3;i++) {
            var retry=(ObjectNode)products.get(sku).deepCopy();
            products.update(sku,retry);
            assertEquals(expected,products.get(sku).path("taxIncludedPriceCny").asDouble());
        }
        assertEquals(history,jdbc.queryForMap("select * from quotation_record where id=?",id));
    }

    @Test void customStockNotesRoundTripWithAuditAndBlankPastePreservesExistingNote() {
        var sku = sku();
        var initial = full(sku).put("stockStatus", "定制款，7天交货");
        paste.confirm(confirmation(List.of(initial)));
        var before = products.get(sku);
        assertEquals("定制款，7天交货", before.path("stockStatus").asText());
        assertTrue(before.path("quoteReady").asBoolean());
        var update = mapper.createObjectNode().put("sku", sku).put("stockStatus", "少量现货");
        var preview = paste.preview(List.of(update)).rows().getFirst();
        assertEquals("update", preview.action());
        assertEquals(1, preview.changes().size());
        paste.confirm(confirmation(List.of(update)));
        var after = products.get(sku);
        assertEquals("少量现货", after.path("stockStatus").asText());
        assertTrue(after.path("quoteReady").asBoolean());
        assertEquals(before.path("purchasePriceCny"), after.path("purchasePriceCny"));
        var history = products.history(sku, PageRequest.of(0, 1)).getContent().getFirst();
        assertEquals("采购粘贴更新", history.operation());
        assertEquals("stockStatus", history.changes().get(0).path("field").asText());
        assertEquals("定制款，7天交货", history.changes().get(0).path("before").asText());
        assertEquals("少量现货", history.changes().get(0).path("after").asText());
        long historyBefore = historyCount(sku);
        var blank = mapper.createObjectNode().put("sku", sku).put("stockStatus", " ");
        paste.confirm(confirmation(List.of(blank)));
        assertEquals("少量现货", products.get(sku).path("stockStatus").asText());
        assertEquals(historyBefore, historyCount(sku));
        for (var status : List.of("", "无货")) {
            var another = sku();
            paste.confirm(confirmation(List.of(full(another).put("stockStatus", status))));
            assertTrue(products.get(another).path("quoteReady").asBoolean());
        }
    }

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

    @Test void sharedEndpointMixesLegacyUpdatesAndNewRowsWithoutConvertingPricesOrHistoricalRecords() throws Exception {
        var auto = sku(); var fixed = sku(); var added = sku();
        var automatic = full(auto).put("dataSource", "legacy_2026").put("legacyPriceMode", "auto_tax")
                .put("sourceQuotedPriceCny", 100).put("taxPoint", .1).put("sourceSheet", "2026原始采购表")
                .put("sourceRow", 88).put("skuOrigin", "imported").put("productImage", "/legacy-product.png")
                .put("physicalImage", "/legacy-physical.png").put("notes", "原始备注")
                .put("tier2MinQty", 10).put("tier2PriceCny", 95).put("tier3MinQty", 100).put("tier3PriceCny", 90);
        automatic.remove("freight10Cny");
        products.upsert(automatic);
        var original = products.get(auto);
        var originalId = repository.findBySku(auto).orElseThrow().id;
        var fixedPrice = full(fixed).put("dataSource", "legacy_2026").put("taxIncludedPriceCny", 125);
        fixedPrice.remove("freight10Cny");
        products.upsert(fixedPrice);
        var originalFixed = products.get(fixed);
        long autoHistory = historyCount(auto), fixedHistory = historyCount(fixed);
        assertEquals(110, original.path("purchasePriceCny").asDouble());
        var quoteId = UUID.randomUUID();
        var payload = mapper.createObjectNode().put("primarySku", auto).put("purchaseUnitPriceCny", 110)
                .put("systemQuote", 200).put("customerQuote", 210).put("freight", 10)
                .put("financeReviewStatus", "approved").put("dealStatus", "won");
        jdbc.update("insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at,lifecycle_state) values(?,?,?,'processed',cast(? as jsonb),3,now(),now(),'active')",
                quoteId, "LEGACY-SHARED-" + quoteId.toString().substring(0, 8), "PASTEBUYER", payload.toString());
        jdbc.update("insert into fob_purchase_product(sku,payload,version,created_at,updated_at) values(?,cast(? as jsonb),0,now(),now())",
                auto, "{\"purchasePriceCny\":110,\"notes\":\"原FOB专用资料\"}");
        var historicalQuote = jdbc.queryForMap("select * from quotation_record where id=?", quoteId);
        var historicalFob = jdbc.queryForMap("select * from fob_purchase_product where sku=?", auto);
        List<JsonNode> rows = List.of(full(added), patch(auto, 80).put("notes", " ").put("sourceRow", 2)
                        .put("tier4MinQty", 500).put("tier4PriceCny", 70).put("tier5MinQty", 1000).put("tier5PriceCny", 60),
                patch(fixed, 80).put("color", "蓝色"));

        mvc.perform(post("/api/v1/purchase-products/paste/shared/preview").with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(rows)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[1].effective.purchasePriceCny").value(88))
                .andExpect(jsonPath("$.data.rows[2].effective.purchasePriceCny").value(125));
        assertEquals(original, products.get(auto));
        assertEquals(originalFixed, products.get(fixed));
        assertFalse(products.exists(added));
        var preview = paste.preview(rows, true);
        assertEquals("standard", preview.rows().get(0).effective().path("dataSource").asText());
        assertEquals("shared", preview.rows().get(0).effective().path("purchaseUsage").asText());
        for (var row : preview.rows().subList(1, 3)) {
            assertEquals("legacy_2026", row.effective().path("dataSource").asText());
            assertEquals("tax_included", row.effective().path("purchasePriceBasis").asText());
            assertNotEquals("shared", row.effective().path("purchaseUsage").asText());
            assertFalse(row.effective().hasNonNull("freight10Cny"));
        }
        var request = new PurchasePasteService.Confirmation(rows, preview.rows().stream().map(PurchasePasteService.PreviewRow::expected).toList());
        mvc.perform(post("/api/v1/purchase-products/paste/shared/confirm").with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.added.length()").value(1))
                .andExpect(jsonPath("$.data.updated.length()").value(2));

        var saved = products.get(auto);
        assertEquals(80, saved.path("sourceQuotedPriceCny").asDouble());
        assertEquals(88, saved.path("purchasePriceCny").asDouble());
        assertEquals(88, saved.path("taxIncludedPriceCny").asDouble());
        assertEquals(500, saved.path("tier4MinQty").asInt());
        assertEquals(70, saved.path("tier4PriceCny").asDouble());
        assertEquals(1000, saved.path("tier5MinQty").asInt());
        assertEquals(60, saved.path("tier5PriceCny").asDouble());
        assertEquals(originalId, repository.findBySku(auto).orElseThrow().id);
        for (var field : List.of("dataSource", "purchasePriceBasis", "legacyPriceMode", "purchaseUsage", "taxPoint",
                "sourceSheet", "sourceRow", "skuOrigin", "productImage", "physicalImage", "notes", "freight10Cny"))
            assertEquals(original.path(field), saved.path(field), field);
        assertEquals(125, products.get(fixed).path("purchasePriceCny").asDouble());
        assertEquals("legacy_2026", products.get(fixed).path("dataSource").asText());
        assertNotEquals("shared", products.get(fixed).path("purchaseUsage").asText());
        assertEquals("shared", products.get(added).path("purchaseUsage").asText());
        assertEquals("standard", products.get(added).path("dataSource").asText());
        assertEquals(autoHistory + 1, historyCount(auto));
        assertEquals(fixedHistory + 1, historyCount(fixed));
        var audit = products.history(auto, PageRequest.of(0, 1)).getContent().getFirst();
        assertEquals("采购粘贴更新", audit.operation());
        var changedFields = new HashSet<String>();
        for (var change : audit.changes()) changedFields.add(change.path("field").asText());
        assertTrue(changedFields.containsAll(List.of("purchasePriceCny", "sourceQuotedPriceCny", "taxIncludedPriceCny")));
        assertFalse(changedFields.contains("dataSource"));
        assertFalse(changedFields.contains("purchaseUsage"));
        assertEquals(historicalQuote, jdbc.queryForMap("select * from quotation_record where id=?", quoteId));
        assertEquals(historicalFob, jdbc.queryForMap("select * from fob_purchase_product where sku=?", auto));
    }

    @Test void sharedLegacyVersionConflictRejectsTheEntireMixedBatchWithoutProductOrAuditWrites() {
        var legacy = sku(); var added = sku();
        var initial = full(legacy).put("dataSource", "legacy_2026").put("legacyPriceMode", "auto_tax")
                .put("sourceQuotedPriceCny", 100).put("taxPoint", .1);
        initial.remove("freight10Cny");
        products.upsert(initial);
        List<JsonNode> rows = List.of(full(added), patch(legacy, 80));
        var expected = paste.preview(rows, true).rows().stream().map(PurchasePasteService.PreviewRow::expected).toList();
        products.update(legacy, ((ObjectNode) products.get(legacy)).put("notes", "另一位采购已更新"));
        var concurrentSave = products.get(legacy);
        long existingHistory = historyCount(legacy);

        var conflict = assertThrows(AppException.class, () -> paste.confirmShared(new PurchasePasteService.Confirmation(rows, expected)));

        assertEquals(409, conflict.status().value());
        assertFalse(products.exists(added));
        assertEquals(0L, jdbc.queryForObject("select count(*) from audit_log where resource_type='purchase-product-history' and detail->>'sku'=?", Long.class, added));
        assertEquals(concurrentSave, products.get(legacy));
        assertEquals(110, products.get(legacy).path("purchasePriceCny").asDouble());
        assertEquals(existingHistory, historyCount(legacy));
    }

    @Test void sharedEndpointPersistsFiveTiersAuditsChangesAndPreservesHistoricalAndFobRows() throws Exception {
        var sku = sku();
        var input = full(sku).put("tier2MinQty",10).put("tier2PriceCny",11).put("tier3MinQty",100).put("tier3PriceCny",10)
            .put("tier4MinQty",500).put("tier4PriceCny",9).put("tier5MinQty",1000).put("tier5PriceCny",8).put("freight100Cny",150);
        jdbc.update("insert into fob_purchase_product(sku,payload,version,created_at,updated_at) values(?,cast(? as jsonb),0,now(),now())",sku,"{\"notes\":\"preserve FOB source\"}");
        var oldFob = jdbc.queryForMap("select * from fob_purchase_product where sku=?",sku);
        List<JsonNode> rows = List.of(input);
        mvc.perform(post("/api/v1/purchase-products/paste/shared/preview").with(csrf()).contentType("application/json").content(mapper.writeValueAsString(rows)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].effective.tier5PriceCny").value(8))
            .andExpect(jsonPath("$.data.rows[0].effective.purchaseUsage").value("shared"));
        assertFalse(products.exists(sku));
        var preview = paste.preview(rows,true);
        var request = new PurchasePasteService.Confirmation(rows,preview.rows().stream().map(PurchasePasteService.PreviewRow::expected).toList());
        mvc.perform(post("/api/v1/purchase-products/paste/shared/confirm").with(csrf()).contentType("application/json").content(mapper.writeValueAsString(request)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.added[0].tier4PriceCny").value(9));
        var saved = products.get(sku);
        assertEquals(8,saved.path("tier5PriceCny").asInt()); assertEquals("shared",saved.path("purchaseUsage").asText());
        var id = UUID.randomUUID();
        jdbc.update("insert into quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at,lifecycle_state) values(?,?,?,'processed',cast(? as jsonb),3,now(),now(),'active')",
            id,"SHARED-"+id.toString().substring(0,8),"PASTEBUYER","{\"systemQuote\":33,\"financeReviewStatus\":\"approved\",\"dealStatus\":\"won\"}");
        var history = jdbc.queryForMap("select * from quotation_record where id=?",id);
        List<JsonNode> change = List.of(mapper.createObjectNode().put("sku",sku).put("tier5PriceCny",0));
        var expected = paste.preview(change,true).rows().stream().map(PurchasePasteService.PreviewRow::expected).toList();
        paste.confirmShared(new PurchasePasteService.Confirmation(change,expected));
        assertEquals(0,products.get(sku).path("tier5PriceCny").asInt()); assertEquals(150,products.get(sku).path("freight100Cny").asInt());
        assertEquals(2,historyCount(sku));
        assertEquals(oldFob,jdbc.queryForMap("select * from fob_purchase_product where sku=?",sku));
        assertEquals(history,jdbc.queryForMap("select * from quotation_record where id=?",id));
        assertThrows(AppException.class,()->paste.confirmShared(new PurchasePasteService.Confirmation(change,expected)));
    }
}
