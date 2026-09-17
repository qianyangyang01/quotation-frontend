package com.milano.quotation.purchase;

import com.milano.quotation.logistics.LogisticsQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@ActiveProfiles("test")
class PurchaseFreightEmployeePermissionTest {
    @Autowired WebApplicationContext context;
    @Autowired PurchaseProductRepository products;
    @Autowired PurchaseProductService service;
    @MockitoBean LogisticsQueryService logistics;
    MockMvc mvc;
    PurchaseProduct product;

    @BeforeEach void setUp() {
        mvc=webAppContextSetup(context).apply(springSecurity()).build();
        when(logistics.manifestRevision()).thenReturn(new LogisticsQueryService.ManifestRevision("published",1));
        var sku="FREIGHT-"+UUID.randomUUID().toString().substring(0,8).toUpperCase();
        var payload=JsonNodeFactory.instance.objectNode().put("sku",sku).put("dataSource","legacy_2026")
                .put("weightG",523).put("singleFreightCny",2).put("purchasePriceCny",12).put("minOrderQty",1);
        product=products.saveAndFlush(PurchaseProduct.create(sku,payload,"ready",true,null));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor employee(String permission) {
        return user("EMPLOYEE").authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"),new SimpleGrantedAuthority("PERM_"+permission));
    }

    private void repairFreight() {
        var payload=(tools.jackson.databind.node.ObjectNode)product.payload.deepCopy();
        payload.put("singleFreightCny",5.5); product.payload=payload;
        product.updatedAt=PurchaseProduct.databaseNow();product=products.saveAndFlush(product);
    }

    @ParameterizedTest @ValueSource(strings={"quote","purchase","allRecords"})
    void authorizedEmployeeReadsSharedUpdatedFreight(String permission) throws Exception {
        mvc.perform(get("/api/v1/purchase-products/"+product.sku).with(employee(permission)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.singleFreightCny").value(2));
        repairFreight();
        mvc.perform(get("/api/v1/purchase-products/"+product.sku).with(employee(permission)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.singleFreightCny").value(5.5))
                .andExpect(jsonPath("$.data._version").value(product.version));
    }

    @Test void quotationEmployeeGetsNewRevisionAndOldDraftIsRejected() throws Exception {
        var old=product.version+":"+product.updatedAt;
        repairFreight();
        mvc.perform(get("/api/v1/quotation-sync").param("sku",product.sku).with(employee("quote")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.purchaseVersions['"+product.sku+"']").value(product.version+":"+product.updatedAt));
        var quote=JsonNodeFactory.instance.objectNode().put("primarySku",product.sku);
        quote.putObject("purchaseVersions").put(product.sku,old);
        assertEquals(409,assertThrows(com.milano.quotation.common.AppException.class,()->service.assertQuotationVersions(quote)).status().value());
        quote.putObject("purchaseVersions").put(product.sku,product.version+":"+product.updatedAt);
        assertDoesNotThrow(()->service.assertQuotationVersions(quote));
    }

    @Test void quotationEmployeeCannotEditProcurement() throws Exception {
        mvc.perform(put("/api/v1/purchase-products/"+product.sku).with(employee("quote")).with(csrf())
                .contentType("application/json").content("{\"singleFreightCny\":999}"))
                .andExpect(status().isForbidden());
        assertEquals(2,service.get(product.sku).path("singleFreightCny").asInt());
    }

    @Test void unrelatedPermissionsAndAnonymousCannotReadPurchase() throws Exception {
        mvc.perform(get("/api/v1/purchase-products/"+product.sku).with(employee("logistics"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/purchase-products/"+product.sku)).andExpect(status().isUnauthorized());
    }
}
