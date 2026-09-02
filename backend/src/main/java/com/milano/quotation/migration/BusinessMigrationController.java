package com.milano.quotation.migration;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import com.milano.quotation.security.QuotationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/migration-jobs/business")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class BusinessMigrationController {
    private final BusinessMigrationService migrations;private final BusinessMigrationSourceService sources;private final BusinessMigrationCoordinator coordinator;private final AuditService audit;
    public BusinessMigrationController(BusinessMigrationService migrations,BusinessMigrationSourceService sources,BusinessMigrationCoordinator coordinator,AuditService audit){this.migrations=migrations;this.sources=sources;this.coordinator=coordinator;this.audit=audit;}

    @PostMapping("/preview") ApiResponse<View> preview(@RequestBody JsonNode report,Authentication auth){var row=migrations.preview(report,account(auth));auditPreview(row);return ApiResponse.ok(View.of(row));}
    @PostMapping(value="/sources",consumes="multipart/form-data") ApiResponse<View> source(@RequestPart("sourceType")String sourceType,@RequestPart("file")MultipartFile file,Authentication auth){var row=migrations.preview(sources.parse(sourceType,file),account(auth));auditPreview(row);return ApiResponse.ok(View.of(row));}
    @GetMapping("/{id}") ApiResponse<View> get(@PathVariable UUID id){return ApiResponse.ok(View.of(migrations.get(id)));}
    @GetMapping("/{id}/diff") ApiResponse<JsonNode> diff(@PathVariable UUID id){return ApiResponse.ok(migrations.get(id).diff.deepCopy());}
    @GetMapping("/{id}/errors") ApiResponse<JsonNode> errors(@PathVariable UUID id){return ApiResponse.ok(migrations.get(id).errors.deepCopy());}
    @PostMapping("/{id}/approve") ApiResponse<View> approve(@PathVariable UUID id,@RequestBody JsonNode body,Authentication auth){var row=migrations.approve(id,body,account(auth));audit.record("migration.business-approve","business-migration",id.toString(),"success",Map.of("count",body.path("approvedEntryKeys").size()));return ApiResponse.ok(View.of(row));}
    @PostMapping("/{id}/execute") ApiResponse<View> execute(@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,Authentication auth){validateKey(key);var current=migrations.get(id);if("completed".equals(current.status)){if(key.equals(current.requestId))return ApiResponse.ok(View.of(current));throw AppException.conflict("迁移批次已由其他幂等请求执行完成");}var row=coordinator.execute(id,account(auth),key);audit.record("migration.business-execute","business-migration",id.toString(),"success",Map.of("sourceHash",row.sourceHash));return ApiResponse.ok(View.of(row));}
    @PostMapping("/{id}/rollback") ApiResponse<View> rollback(@PathVariable UUID id,@RequestHeader("Idempotency-Key")String key,Authentication auth){validateKey(key);var current=migrations.get(id);if("rolled_back".equals(current.status))return ApiResponse.ok(View.of(current));var row=coordinator.rollback(id);audit.record("migration.business-rollback","business-migration",id.toString(),"success",Map.of("actor",account(auth)));return ApiResponse.ok(View.of(row));}

    private void auditPreview(BusinessMigrationBatch row){audit.record("migration.business-preview","business-migration",row.id.toString(),"success",Map.of("total",row.counts.path("total").asInt(),"migrate",row.counts.path("migrate").asInt(),"exclude",row.counts.path("exclude").asInt(),"review",row.counts.path("review").asInt()));}
    private static void validateKey(String key){if(key==null||!key.matches("[A-Za-z0-9._:-]{8,120}"))throw AppException.unprocessable("缺少或无效的 Idempotency-Key");}
    private static String account(Authentication auth){return((QuotationPrincipal)auth.getPrincipal()).account();}
    record View(UUID id,String sourceOrigin,String sourceHash,String sourceType,String status,String requestedBy,JsonNode counts,JsonNode report,JsonNode diff,JsonNode errors,JsonNode checkpoint,String requestId,String lastError,long version,java.time.Instant createdAt,java.time.Instant updatedAt,java.time.Instant completedAt){static View of(BusinessMigrationBatch row){return new View(row.id,row.sourceOrigin,row.sourceHash,row.sourceType,row.status,row.requestedBy,row.counts,row.report,row.diff,row.errors,row.checkpoint,row.requestId,row.lastError,row.version,row.createdAt,row.updatedAt,row.completedAt);}}
}
