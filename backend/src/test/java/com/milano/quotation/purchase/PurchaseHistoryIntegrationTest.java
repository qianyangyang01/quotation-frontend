package com.milano.quotation.purchase;

import com.milano.quotation.audit.AuditLogRepository;
import com.milano.quotation.security.UserAccount;
import com.milano.quotation.security.UserAccountRepository;
import com.milano.quotation.security.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:purchase-history;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class PurchaseHistoryIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired PurchaseProductService products;
    @Autowired PurchaseProductRepository repository;
    @Autowired AuditLogRepository logs;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired UserAccountRepository users;
    @Autowired UserAccountService accounts;
    @MockitoBean PurchaseProductDeletionGuard deletionGuard;
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc=webAppContextSetup(context).apply(springSecurity()).build();
        when(deletionGuard.inspect(any(),anyString(),anyLong())).thenAnswer(call -> new PurchaseProductDeletionGuard.DeletionCheck(true,call.getArgument(2),0,0,0,0,0));
    }
    private ObjectNode input(String sku) { return mapper.createObjectNode().put("sku",sku).put("weightG",80).put("minOrderQty",1).put("purchasePriceCny",12).put("quotationOwner","资料报价人"); }
    private String sku() { return "H-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(java.util.Locale.ROOT); }

    @Test @WithMockUser(username="BUYER", authorities="PERM_purchase")
    void savesExactDiffWithServerActorAndRejectsConflictsWithoutHistory() throws Exception {
        var sku=sku(); var saved=(ObjectNode)products.upsert(input(sku));
        var updated=saved.deepCopy().put("purchasePriceCny",15).put("notes","维护备注").put("actorAccount","伪造账号");
        users.saveAndFlush(UserAccount.create("BUYER","采购小黄","unused-test-hash","purchase",false));
        var principal=accounts.loadUserByUsername("BUYER");
        var auth=new UsernamePasswordAuthenticationToken(principal,"",principal.getAuthorities());
        mvc.perform(post("/api/v1/purchase-products/{sku}/maintenance",sku).with(csrf()).with(authentication(auth)).contentType("application/json").content(updated.toString()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/purchase-products/{sku}/history",sku).param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].actorAccount").value("BUYER"))
                .andExpect(jsonPath("$.data.items[0].actorName").value("采购小黄"))
                .andExpect(jsonPath("$.data.items[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].changes.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].changes[0].before").value(12))
                .andExpect(jsonPath("$.data.items[0].changes[0].after").value(15));
        mvc.perform(post("/api/v1/purchase-products/{sku}/maintenance",sku).with(csrf()).contentType("application/json").content(updated.toString()))
                .andExpect(status().isConflict());
        assertEquals(2,products.history(sku,PageRequest.of(0,10)).getTotalElements());
        products.update(sku,products.get(sku));
        assertEquals(2,products.history(sku,PageRequest.of(0,10)).getTotalElements(),"unchanged save creates no history");
        assertEquals(1,products.history(sku,PageRequest.of(1,1)).getNumberOfElements());
    }

    @Test @WithMockUser(username="BUYER", authorities="PERM_purchase")
    void renameKeepsIdentityAndHistoryAndDeletionRecreationDoesNotMixProducts() {
        var original=sku(); var target=sku(); var saved=(ObjectNode)products.upsert(input(original));
        var id=repository.findBySku(original).orElseThrow().id;
        var renamed=products.update(original,saved.deepCopy().put("sku",target));
        assertEquals(id,repository.findBySku(target).orElseThrow().id);
        assertFalse(products.exists(original));
        assertEquals(2,products.history(target,PageRequest.of(0,10)).getTotalElements());
        assertEquals("sku",products.history(target,PageRequest.of(0,10)).getContent().getFirst().changes().get(0).path("field").asText());
        products.delete(target,renamed.path("_version").asLong());
        products.upsert(input(target));
        assertEquals(1,products.history(target,PageRequest.of(0,10)).getTotalElements());
        assertEquals(2,logs.findByResourceTypeAndResourceIdOrderByCreatedAtDescIdDesc("purchase-product-history",id.toString(),PageRequest.of(0,10)).getTotalElements());
    }

    @Test @WithMockUser(username="BUYER", authorities="PERM_purchase")
    void rollbackAndFailedBatchLeaveBothProductAndHistoryUnchanged() {
        var sku=sku(); products.upsert(input(sku));
        var transaction=new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status->{
            var changed=((ObjectNode)products.get(sku)).put("weightG",95);
            products.update(sku,changed); status.setRollbackOnly();
        });
        assertEquals(80,products.get(sku).path("weightG").asInt());
        assertEquals(1,products.history(sku,PageRequest.of(0,10)).getTotalElements());
        var added=sku();
        assertThrows(RuntimeException.class,()->products.upsertAll(List.of(input(added),input(sku))));
        assertFalse(products.exists(added));
    }

    @Test @WithMockUser(username="BUYER", authorities="PERM_purchase")
    void recordsClearedFieldsImageRemovalAndCatalogChanges() {
        var sku=sku(); var saved=(ObjectNode)products.upsert(input(sku).put("notes","旧备注").put("productImage","/old-image.png"));
        products.update(sku,saved.put("notes","").put("productImage",""));
        var entry=products.history(sku,PageRequest.of(0,10)).getContent().getFirst();
        assertEquals(2,entry.changes().size());
        assertTrue(entry.changes().get(0).path("after").isNull());
        assertEquals("",products.get(sku).path("image").asText());
        products.changeCatalogState(sku,"disabled",products.get(sku).path("_version").asLong());
        assertEquals("停用商品",products.history(sku,PageRequest.of(0,10)).getContent().getFirst().operation());
    }

    @Test @WithMockUser(authorities="PERM_quote")
    void historyAndMaintenanceRequirePurchasePermission() throws Exception {
        mvc.perform(get("/api/v1/purchase-products/SKU/history")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/purchase-products/SKU/maintenance").with(csrf()).contentType("application/json").content(input("SKU").toString())).andExpect(status().isForbidden());
    }

    @Test @WithMockUser(username="BUYER", authorities="PERM_purchase")
    void referencedOrDuplicateSkuRenamePreservesOriginalAndHistory() {
        var original=sku(); var target=sku(); var saved=(ObjectNode)products.upsert(input(original));
        products.upsert(input(target));
        assertThrows(RuntimeException.class,()->products.update(original,saved.deepCopy().put("sku",target)));
        when(deletionGuard.inspect(any(),anyString(),anyLong())).thenReturn(new PurchaseProductDeletionGuard.DeletionCheck(false,0,0,1,0,0,0));
        assertThrows(RuntimeException.class,()->products.update(original,saved.deepCopy().put("sku",sku())));
        assertTrue(products.exists(original));
        assertEquals(1,products.history(original,PageRequest.of(0,10)).getTotalElements());
    }
}
