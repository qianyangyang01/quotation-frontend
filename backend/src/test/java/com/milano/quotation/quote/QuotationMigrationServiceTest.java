package com.milano.quotation.quote;

import com.milano.quotation.common.AppException;
import com.milano.quotation.security.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QuotationMigrationServiceTest {
    private QuotationTemplateRepository templates;
    private QuotationRecordRepository records;
    private UserAccountRepository users;
    private QuotationMigrationService service;

    @BeforeEach
    void setup() {
        templates = mock(QuotationTemplateRepository.class);
        records = mock(QuotationRecordRepository.class);
        users = mock(UserAccountRepository.class);
        service = new QuotationMigrationService(templates, records, users);
        when(records.findById(any())).thenReturn(Optional.empty());
        when(records.findByQuoteNo(any())).thenReturn(Optional.empty());
    }

    @Test
    void historicalQuotationRequiresExplicitSalespersonMapping() {
        var values = JsonNodeFactory.instance.arrayNode();
        values.addObject().put("id", "legacy-1").put("no", "QT-LEGACY-1").put("salespersonName", "钱洋洋");
        assertThrows(AppException.class, () -> service.applyRecords(values, JsonNodeFactory.instance.objectNode(), "ADMIN", "a".repeat(64)));
        verify(records, never()).save(any());
    }

    @Test
    void historicalQuotationUsesApprovedSalespersonMapping() {
        var values = JsonNodeFactory.instance.arrayNode();
        values.addObject().put("id", "legacy-1").put("no", "QT-LEGACY-1").put("salespersonName", "钱洋洋");
        var mappings = JsonNodeFactory.instance.objectNode().put("钱洋洋", "EMP001");
        when(users.existsByAccountIgnoreCase("EMP001")).thenReturn(true);
        var result = service.applyRecords(values, mappings, "ADMIN", "a".repeat(64));
        assertEquals(1, result.path("createdQuotations").size());
        verify(records).save(argThat(row -> "EMP001".equals(row.ownerAccount)));
    }

    @Test
    void templateMigrationCreatesSkipsIdenticalAndRejectsConflicts() {
        assertThrows(AppException.class, () -> service.applyTemplates(JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(), "ADMIN", "b".repeat(64)));
        when(users.existsByAccountIgnoreCase("ADMIN")).thenReturn(true);
        when(templates.findById(any())).thenReturn(Optional.empty());
        var values = JsonNodeFactory.instance.arrayNode();
        values.addObject().put("id", "template-1").put("name", "基础模板").put("createdAt", "2026-08-01T00:00:00Z");
        var first = service.applyTemplates(values, JsonNodeFactory.instance.objectNode(), "ADMIN", "b".repeat(64));
        assertEquals(1, first.path("createdTemplates").size());
        var template = org.mockito.ArgumentCaptor.forClass(QuotationTemplateEntity.class);
        verify(templates).save(template.capture());
        when(templates.findById(any())).thenReturn(Optional.of(template.getValue()));
        assertEquals(0, service.applyTemplates(values, JsonNodeFactory.instance.objectNode(), "ADMIN", "b".repeat(64)).path("createdTemplates").size());
        template.getValue().name = "冲突模板";
        assertThrows(AppException.class, () -> service.applyTemplates(values, JsonNodeFactory.instance.objectNode(), "ADMIN", "b".repeat(64)));
        var invalid = JsonNodeFactory.instance.arrayNode();
        invalid.addObject().put("name", "");
        assertThrows(AppException.class, () -> service.applyTemplates(invalid, JsonNodeFactory.instance.objectNode(), "ADMIN", "b".repeat(64)));
    }

    @Test
    void recordMigrationCoversGeneratedNumbersIdempotencyAndConflicts() {
        assertThrows(AppException.class, () -> service.applyRecords(JsonNodeFactory.instance.objectNode(), JsonNodeFactory.instance.objectNode(), "ADMIN", "c".repeat(64)));
        when(users.existsByAccountIgnoreCase("EMP001")).thenReturn(true);
        var mappings = JsonNodeFactory.instance.objectNode().put("钱洋洋", "EMP001");
        var values = JsonNodeFactory.instance.arrayNode();
        values.addObject().put("id", "legacy-generated").put("salespersonName", "钱洋洋").put("status", "status-that-is-too-long").put("createdAt", "invalid-time");
        var first = service.applyRecords(values, mappings, "ADMIN", "c".repeat(64));
        assertEquals(1, first.path("createdQuotations").size());
        var record = org.mockito.ArgumentCaptor.forClass(QuotationRecordEntity.class);
        verify(records).save(record.capture());
        assertTrue(record.getValue().quoteNo.startsWith("MIG"));
        assertEquals("pending", record.getValue().status);
        when(records.findById(any())).thenReturn(Optional.of(record.getValue()));
        assertEquals(0, service.applyRecords(values, mappings, "ADMIN", "c".repeat(64)).path("createdQuotations").size());
        record.getValue().status = "conflict";
        assertThrows(AppException.class, () -> service.applyRecords(values, mappings, "ADMIN", "c".repeat(64)));

        when(records.findById(any())).thenReturn(Optional.empty());
        when(records.findByQuoteNo("QT-DUPLICATE")).thenReturn(Optional.of(record.getValue()));
        var duplicate = JsonNodeFactory.instance.arrayNode();
        duplicate.addObject().put("id", "legacy-duplicate").put("no", "QT-DUPLICATE").put("salespersonName", "钱洋洋");
        assertThrows(AppException.class, () -> service.applyRecords(duplicate, mappings, "ADMIN", "d".repeat(64)));
        var tooLong = JsonNodeFactory.instance.arrayNode();
        tooLong.addObject().put("id", "legacy-long").put("no", "Q".repeat(41)).put("salespersonName", "钱洋洋");
        assertThrows(AppException.class, () -> service.applyRecords(tooLong, mappings, "ADMIN", "d".repeat(64)));
    }

    @Test
    void rollbackRemovesOnlyExistingMigrationRows() {
        var quotationId = UUID.randomUUID();
        var missingQuotationId = UUID.randomUUID();
        var templateId = UUID.randomUUID();
        var missingTemplateId = UUID.randomUUID();
        when(records.existsById(quotationId)).thenReturn(true);
        when(records.existsById(missingQuotationId)).thenReturn(false);
        when(templates.existsById(templateId)).thenReturn(true);
        when(templates.existsById(missingTemplateId)).thenReturn(false);
        var execution = JsonNodeFactory.instance.objectNode();
        execution.putArray("createdQuotations").add(quotationId.toString()).add(missingQuotationId.toString()).add("invalid");
        execution.putArray("createdTemplates").add(templateId.toString()).add(missingTemplateId.toString()).add("invalid");
        var result = service.rollback(execution);
        assertEquals(1, result.path("quotationsRemoved").asInt());
        assertEquals(1, result.path("templatesRemoved").asInt());
        verify(records).deleteById(quotationId);
        verify(templates).deleteById(templateId);
    }
}
