package com.milano.quotation.migration;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BusinessMigrationServiceTest {
    private BusinessMigrationBatchRepository repository;private BusinessMigrationService service;
    @BeforeEach void setup(){repository=mock(BusinessMigrationBatchRepository.class);when(repository.findBySourceHash(any())).thenReturn(Optional.empty());when(repository.saveAndFlush(any())).thenAnswer(call->call.getArgument(0));service=new BusinessMigrationService(repository,JsonMapper.builder().build());}
    @Test void createsPendingReviewWithExecutionMetadata(){var row=service.preview(report(false),"ADMIN");assertEquals("pending_review",row.status);assertEquals(BusinessMigrationService.LEGACY_BROWSER,row.sourceType);assertEquals(1,row.counts.path("migrate").asInt());assertTrue(row.errors.isEmpty());assertNotNull(row.updatedAt);}
    @Test void approvesOnlyKnownEntriesAndPreservesMappings(){var row=service.preview(report(false),"ADMIN");when(repository.findById(row.id)).thenReturn(Optional.of(row));var body=JsonNodeFactory.instance.objectNode();body.putArray("approvedEntryKeys").add("localStorage/milano.finance-exchange-rate.v1");body.putObject("ownerMappings").put("admin","ADMIN");var approved=service.approve(row.id,body,"ADMIN");assertEquals("approved",approved.status);assertEquals("ADMIN",approved.report.path("ownerMappings").path("admin").asText());}
    @Test void blocksApprovalWhenValidationErrorsRemain(){var row=service.preview(report(true),"ADMIN");when(repository.findById(row.id)).thenReturn(Optional.of(row));var body=JsonNodeFactory.instance.objectNode();body.putArray("approvedEntryKeys").add("localStorage/milano.finance-exchange-rate.v1");assertThrows(AppException.class,()->service.approve(row.id,body,"ADMIN"));}
    @Test void rejectsExcludedOrReviewEntriesFromApprovalWhitelist(){var source=report(false);((tools.jackson.databind.node.ObjectNode)source.path("entries").get(0)).put("decision","exclude").remove("value");var row=service.preview(source,"ADMIN");when(repository.findById(row.id)).thenReturn(Optional.of(row));var body=JsonNodeFactory.instance.objectNode();body.putArray("approvedEntryKeys").add("localStorage/milano.finance-exchange-rate.v1");assertThrows(AppException.class,()->service.approve(row.id,body,"ADMIN"));}
    @Test void rejectsSensitiveAndForeignOrigins(){var sensitive=report(false);((tools.jackson.databind.node.ObjectNode)sensitive.path("entries").get(0).path("value")).put("accessToken","bad");assertThrows(AppException.class,()->service.preview(sensitive,"ADMIN"));var foreign=report(false);foreign.put("sourceOrigin","https://example.com");assertThrows(AppException.class,()->service.preview(foreign,"ADMIN"));}
    @Test void revalidatesPendingBatchByStableSourceFileHash(){var original=report(true);original.put("sourceFileSha256","a".repeat(64));var existing=service.preview(original,"ADMIN");var corrected=report(false);corrected.put("sourceFileSha256","a".repeat(64));when(repository.findBySourceHash("a".repeat(64))).thenReturn(Optional.of(existing));var result=service.preview(corrected,"ADMIN");assertSame(existing,result);assertTrue(result.errors.isEmpty());assertTrue(result.checkpoint.has("revalidatedAt"));}
    private tools.jackson.databind.node.ObjectNode report(boolean error){var report=JsonNodeFactory.instance.objectNode().put("schemaVersion",2).put("sourceType",BusinessMigrationService.LEGACY_BROWSER).put("sourceOrigin","http://127.0.0.1:5173");var entry=report.putArray("entries").addObject();entry.put("source","localStorage").put("container","http://127.0.0.1:5173").put("key","milano.finance-exchange-rate.v1").put("category","finance").put("decision","migrate").put("count",1);entry.putObject("value").put("usdCny",7.1);var errors=report.putArray("errors");if(error)errors.addObject().put("message","blocked");return report;}
}
