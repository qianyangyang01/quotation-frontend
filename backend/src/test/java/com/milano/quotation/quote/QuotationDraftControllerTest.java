package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import com.milano.quotation.security.QuotationPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QuotationDraftControllerTest {
    private QuotationDraftRepository drafts;
    private QuotationDraftController controller;
    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach void setup() {
        drafts = mock(QuotationDraftRepository.class);
        controller = new QuotationDraftController(drafts);
        var principal = new QuotationPrincipal(UUID.randomUUID(), "ADMIN", "管理员", "hash", "superadmin", true, false, List.of("quote"));
        auth = new UsernamePasswordAuthenticationToken(principal, "", principal.getAuthorities());
    }

    @Test void returnsExplicitEmptyStateAndCreatesVersionedDraft() {
        when(drafts.findById("ADMIN")).thenReturn(Optional.empty());
        var empty = controller.state(auth).data();
        assertFalse(empty.path("exists").asBoolean());
        assertEquals(-1, empty.path("version").asInt());
        when(drafts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var saved = controller.saveState(validDraft(), -1, auth).data();
        assertTrue(saved.path("exists").asBoolean());
        assertEquals("客户A", saved.path("payload").path("customerName").asText());
    }

    @Test void rejectsStaleVersionAndProtectsNewerDraftFromDelete() {
        var row = row(3);
        when(drafts.findById("ADMIN")).thenReturn(Optional.of(row));
        assertThrows(AppException.class, () -> controller.saveState(validDraft(), 2, auth));
        assertThrows(AppException.class, () -> controller.deleteState(2, auth));
        verify(drafts, never()).delete(any());
    }

    @Test void treatsIdenticalPayloadAsIdempotentAndRejectsSensitiveContent() {
        var payload = validDraft();
        var row = row(4); row.payload = payload.deepCopy();
        when(drafts.findById("ADMIN")).thenReturn(Optional.of(row));
        assertEquals(4, controller.saveState(payload, 4, auth).data().path("version").asInt());
        verify(drafts, never()).saveAndFlush(any());
        var unsafe = validDraft(); unsafe.putObject("product").put("customerId", "secret");
        assertThrows(AppException.class, () -> controller.saveState(unsafe, 4, auth));
        var dataUrl = validDraft(); dataUrl.putObject("product").put("sku", "data:image/png;base64,AAAA");
        assertThrows(AppException.class, () -> controller.saveState(dataUrl, 4, auth));
    }

    @Test void updatesAndDeletesOnlyTheExpectedVersion() {
        var row = row(5);
        when(drafts.findById("ADMIN")).thenReturn(Optional.of(row));
        when(drafts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var updated = validDraft().put("customerName", "客户B");
        assertEquals("客户B", controller.saveState(updated, 5, auth).data().path("payload").path("customerName").asText());
        assertDoesNotThrow(() -> controller.deleteState(5, auth));
        verify(drafts).delete(row);

        reset(drafts);
        when(drafts.findById("ADMIN")).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> controller.deleteState(-1, auth));
        assertThrows(AppException.class, () -> controller.saveState(validDraft(), 0, auth));
    }

    @Test void rejectsInvalidSchemaTopLevelFieldsAndNestedSecrets() {
        assertThrows(AppException.class, () -> controller.saveState(JsonNodeFactory.instance.arrayNode(), -1, auth));
        assertThrows(AppException.class, () -> controller.saveState(validDraft().put("schemaVersion", 1), -1, auth));
        assertThrows(AppException.class, () -> controller.saveState(validDraft().put("unexpected", true), -1, auth));

        var nestedSecret = validDraft();
        nestedSecret.putArray("bundleItems").addObject().put("session_token", "secret");
        assertThrows(AppException.class, () -> controller.saveState(nestedSecret, -1, auth));

        var nestedDataUrl = validDraft();
        nestedDataUrl.putArray("commonSelections").add("  DATA:image/png;base64,AAAA");
        assertThrows(AppException.class, () -> controller.saveState(nestedDataUrl, -1, auth));
    }

    private tools.jackson.databind.node.ObjectNode validDraft() {
        return JsonNodeFactory.instance.objectNode().put("schemaVersion", 2).put("customerName", "客户A")
                .put("quoteMode", "single").put("skuSearch", "SKU-1").put("productCategory", "服装")
                .put("logisticsAttribute", "普货").put("selectedCustomerGrade", "S")
                .put("selectedTaxCustomerType", "A").put("monthlySalesEstimate", "10")
                .put("customQuoteQuantity", 5).put("quoteMatrixMode", "common");
    }
    private QuotationDraftEntity row(long version) {
        var row = new QuotationDraftEntity(); row.ownerAccount = "ADMIN"; row.payload = validDraft(); row.version = version; row.updatedAt = Instant.now(); return row;
    }
}
