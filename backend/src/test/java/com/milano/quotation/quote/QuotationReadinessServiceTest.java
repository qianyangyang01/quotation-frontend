package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import com.milano.quotation.finance.FinanceSetting;
import com.milano.quotation.finance.FinanceSettingRepository;
import com.milano.quotation.logistics.LogisticsService;
import com.milano.quotation.purchase.PurchaseProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuotationReadinessServiceTest {
    private PurchaseProductService products;
    private LogisticsService logistics;
    private FinanceSettingRepository finance;
    private QuotationReadinessService service;

    @BeforeEach
    void setup() {
        products = mock(PurchaseProductService.class);
        logistics = mock(LogisticsService.class);
        finance = mock(FinanceSettingRepository.class);
        service = new QuotationReadinessService(products, logistics, finance);
        when(products.notQuoteReadyLocked(anyCollection())).thenReturn(List.of("TESTP260001"));
    }

    @Test
    void reportsAllMissingBusinessConditionsAndBlocksCreation() {
        var state = service.snapshot();
        assertFalse(state.path("ready").asBoolean());
        assertEquals(3, state.path("missing").size());
        var exception = assertThrows(AppException.class,
                () -> service.assertCanCreate(JsonNodeFactory.instance.objectNode().put("primarySku", "TESTP260001")));
        assertEquals(422, exception.status().value());
        assertTrue(exception.getMessage().contains("尚未确认转正式"));
    }

    @Test
    void acceptsCompletePurchaseLogisticsAndFinanceState() {
        when(products.readyCount()).thenReturn(1L);
        when(products.notQuoteReadyLocked(anyCollection())).thenReturn(List.of());
        when(logistics.publishedChannelCount()).thenReturn(1L);
        var countries = JsonNodeFactory.instance.arrayNode(); countries.addObject().put("country", "美国");
        var policies = JsonNodeFactory.instance.arrayNode(); policies.addObject().put("channel", "云途");
        var grades = JsonNodeFactory.instance.arrayNode(); grades.addObject().put("grade", "A");
        setting("country-classification", countries);
        setting("channel-policies", policies);
        setting("customer-grades", grades);
        setting("exchange-rate", JsonNodeFactory.instance.objectNode().put("usdCny", 7.12));
        setting("tax-settings", JsonNodeFactory.instance.objectNode()
                .set("countries", JsonNodeFactory.instance.arrayNode().add("US"))
                .set("providers", JsonNodeFactory.instance.arrayNode().add("云途"))
                .put("updatedAt", "2026-08-23T00:00:00Z"));
        var state = service.snapshot();
        assertTrue(state.path("ready").asBoolean(), state.toPrettyString());
        assertDoesNotThrow(() -> service.assertCanCreate(JsonNodeFactory.instance.objectNode().put("primarySku", "BIZ-1")));
    }

    @Test
    void permitsExplicitlySavedNoDutySettingsButNotAnUnloadedSetting() {
        acceptsCompletePurchaseLogisticsAndFinanceState();
        setting("tax-settings", JsonNodeFactory.instance.objectNode()
                .set("countries", JsonNodeFactory.instance.arrayNode())
                .set("providers", JsonNodeFactory.instance.arrayNode())
                .put("updatedAt", "2026-09-05T00:00:00Z"));
        assertTrue(service.snapshot().path("ready").asBoolean());
        when(finance.findById("tax-settings")).thenReturn(Optional.empty());
        assertFalse(service.snapshot().path("ready").asBoolean());
    }

    private void setting(String key, tools.jackson.databind.JsonNode payload) {
        var row = mock(FinanceSetting.class);
        row.payload = payload;
        when(finance.findById(key)).thenReturn(Optional.of(row));
    }
}
