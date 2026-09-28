package com.milano.quotation.purchase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = "app.purchase-sales.source=classpath:purchase-sales-test.json")
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PurchaseSalesPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.sql.init.mode", () -> "never");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.jpa.defer-datasource-initialization", () -> "false");
        r.add("spring.flyway.enabled", () -> "true");
    }
    @Autowired WebApplicationContext context;
    @Autowired PurchaseProductService products;
    @Autowired PurchaseProductRepository repository;
    @Autowired ObjectMapper mapper;

    @Test @WithMockUser(username = "SALESBUYER", authorities = "PERM_purchase")
    void realProjectionMatchesOnlyExactPrimarySkuAndPreservesZeroValuesWithoutWrites() throws Exception {
        products.upsert(mapper.createObjectNode().put("sku", "SALES-PRIMARY").put("quotationOwner", "测试采购")
                .put("weightG", 100).put("minOrderQty", 1).put("purchasePriceCny", 12)
                .put("singleFreightCny", 0).put("freeShipping", "是").put("taxPoint", 0)
                .put("notes", "Do not include this large field"));
        products.upsert(mapper.createObjectNode().put("sku", "SALES-CHILD-ONLY-A").put("weightG", 200)
                .put("minOrderQty", 1).put("purchasePriceCny", 15).put("singleFreightCny", 5).put("taxPoint", 0));
        var before = products.get("SALES-PRIMARY");
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var response = mvc.perform(get("/api/v1/purchase-sales")).andExpect(status().isOk()).andReturn().getResponse();
        var result = mapper.readTree(response.getContentAsByteArray()).path("data");
        assertEquals(3, result.path("source").path("rows").size());
        assertEquals(1, result.path("products").size());
        var product = result.path("products").get(0);
        assertEquals("SALES-PRIMARY", product.path("sku").asText());
        assertEquals(0, product.path("taxPoint").asDouble());
        assertEquals(0, product.path("singleFreightCny").asDouble());
        assertEquals("是", product.path("freeShipping").asText());
        assertFalse(product.has("notes"));
        assertEquals(2, repository.count());
        assertEquals(before, products.get("SALES-PRIMARY"));
    }

    @Test @WithMockUser(authorities = "PERM_quote")
    void quoteOnlyUserCannotReadSalesSource() throws Exception {
        webAppContextSetup(context).apply(springSecurity()).build().perform(get("/api/v1/purchase-sales"))
                .andExpect(status().isForbidden());
    }
}
