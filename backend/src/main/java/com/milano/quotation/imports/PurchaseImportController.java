package com.milano.quotation.imports;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import com.milano.quotation.idempotency.IdempotencyService;
import com.milano.quotation.purchase.PurchaseProductService;
import com.milano.quotation.security.QuotationPrincipal;
import com.milano.quotation.storage.AssetStorageService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/purchase-imports")
@PreAuthorize("hasAuthority('PERM_purchase')")
public class PurchaseImportController {
    private final PurchaseWorkbookService parser;
    private final ImportJobRepository jobs;
    private final PurchaseImportRowRepository rows;
    private final PurchaseProductService products;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final AssetStorageService storage;

    public PurchaseImportController(PurchaseWorkbookService parser, ImportJobRepository jobs,
                                    PurchaseImportRowRepository rows, PurchaseProductService products,
                                    IdempotencyService idempotency, AuditService audit, AssetStorageService storage) {
        this.parser = parser;
        this.jobs = jobs;
        this.rows = rows;
        this.products = products;
        this.idempotency = idempotency;
        this.audit = audit;
        this.storage = storage;
    }

    @PostMapping(value = "/preview", consumes = "multipart/form-data")
    ApiResponse<?> preview(@RequestPart("file") MultipartFile file, Authentication auth) {
        var result = parser.preview(file, account(auth));
        audit.record("purchase.import-preview", "purchase-import", result.jobId().toString(), "success",
                Map.of("file", result.fileName(), "rows", result.records().size()));
        return ApiResponse.ok(result);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    ApiResponse<?> job(@PathVariable UUID id) {
        var job = jobs.findById(id).orElseThrow(() -> AppException.notFound("导入任务不存在"));
        return ApiResponse.ok(Map.of(
                "id", job.id,
                "status", job.status,
                "sourceName", job.sourceName,
                "summary", job.payload,
                "records", rows.findByJobIdOrderBySourceRow(id).stream().map(row -> row.payload).toList()));
    }

    @PostMapping("/{id}/confirm")
    @Transactional
    ApiResponse<?> confirm(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                           @RequestBody(required = false) ConfirmInput input, Authentication auth) {
        var job = jobs.findById(id).orElseThrow(() -> AppException.notFound("导入任务不存在"));
        var importMode=input==null||input.importMode()==null||input.importMode().isBlank()?"formal":input.importMode();
        if(!java.util.Set.of("formal","pending_template").contains(importMode))throw AppException.unprocessable("采购导入模式不合法");
        var request = JsonNodeFactory.instance.objectNode().put("jobId", id.toString()).put("importMode",importMode);
        var existing = idempotency.existing(account(auth), "purchase-import-confirm", key, request);
        if (existing.isPresent()) return ApiResponse.ok(existing.get());
        if (!job.status.equals("preview")) throw AppException.conflict("该导入任务不能再次确认");
        if (!job.payload.path("canConfirm").asBoolean(false)) {
            audit.record("purchase.import-confirm", "purchase-import", id.toString(), "rejected",
                    Map.of("reason", "blocking-validation-errors"));
            throw AppException.unprocessable("导入预览存在阻断错误，请修正Excel后重新上传");
        }

        var imported = new ArrayList<JsonNode>();
        var stagedRows = rows.findByJobIdOrderBySourceRow(id);
        for (var row : stagedRows) {
            imported.add(products.upsertImported(row.payload, row.productAssetId, row.physicalAssetId,importMode,job.sourceHash));
        }
        storage.publish(stagedRows.stream()
                .flatMap(row -> java.util.stream.Stream.of(row.productAssetId, row.physicalAssetId))
                .filter(java.util.Objects::nonNull).toList());
        job.status = "completed";
        job.updatedAt = Instant.now();
        job.completedAt = job.updatedAt;
        var response = JsonNodeFactory.instance.objectNode()
                .put("jobId", id.toString())
                .put("imported", imported.size())
                .put("importMode",importMode)
                .put("status", "completed");
        idempotency.save(account(auth), "purchase-import-confirm", key, request, response);
        audit.record("purchase.import-confirm", "purchase-import", id.toString(), "success",
                Map.of("count", imported.size(),"importMode",importMode));
        return ApiResponse.ok(response);
    }

    record ConfirmInput(String importMode) {}

    private static String account(Authentication auth) {
        return ((QuotationPrincipal) auth.getPrincipal()).account();
    }
}
