package com.milano.quotation.quote;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.*;
import com.milano.quotation.security.QuotationPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/quotations/lifecycle")
public class QuotationLifecycleController {
    private final QuotationRecordRepository records;
    private final AuditService audit;
    private final QuotationReviewService reviews;
    public QuotationLifecycleController(QuotationRecordRepository records, AuditService audit, QuotationReviewService reviews) {
        this.records = records; this.audit = audit; this.reviews = reviews;
    }
    public record Item(UUID id, Long version) {}
    public record Request(String action, String reason, List<Item> items) {}
    public record Result(int changed) {}

    @PostMapping
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional
    public ApiResponse<Result> change(@RequestBody Request request, Authentication auth) {
        if (request.action() == null || !Set.of("archive", "trash", "restore").contains(request.action()))
            throw AppException.unprocessable("清理操作不合法");
        var reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty() || reason.length() > 200) throw AppException.unprocessable("请填写1至200字的操作原因");
        if (request.items() == null || request.items().isEmpty() || request.items().size() > 100)
            throw AppException.unprocessable("每次请选择1至100条报价记录");
        var versions = new TreeMap<UUID,Long>();
        for (var item : request.items()) {
            if (item == null || item.id() == null || item.version() == null || item.version() < 0 || versions.put(item.id(), item.version()) != null)
                throw AppException.unprocessable("记录编号或版本不合法，请刷新后重新选择");
        }
        var principal = (QuotationPrincipal) auth.getPrincipal();
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"))
                && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("PERM_allRecords"));
        // Validate the whole batch before writing. A conflict rolls back records and audit together.
        var rows = new ArrayList<QuotationRecordEntity>();
        for (var entry : versions.entrySet()) {
            var row = records.lockById(entry.getKey()).orElseThrow(() -> AppException.notFound("所选报价记录不存在，请刷新"));
            if (!admin && !row.ownerAccount.equals(principal.account())) throw new AccessDeniedException("只能处理自己的报价记录");
            if (row.version != entry.getValue()) throw AppException.conflict("所选报价已变更，本次未执行，请刷新后重新选择");
            if (request.action().equals("restore")) {
                if (row.lifecycleState.equals("active")) throw AppException.conflict("所选记录已在当前记录中，请刷新");
                // Employees cannot undo an administrator's disposition of their records.
                if (!admin && !row.payload.path("lifecycleChangedAccount").asText().equals(principal.account()))
                    throw new AccessDeniedException("请由管理员恢复此记录");
            } else {
                if (row.lifecycleState.equals("trashed") || request.action().equals("archive") && !row.lifecycleState.equals("active"))
                    throw AppException.conflict("所选记录状态已变化，请刷新");
                if (protectedRecord(row)) throw AppException.conflict("包含已成交、已有成交明细或审核中/已审核记录，本次未执行；请管理员单独核实");
            }
            rows.add(row);
        }
        var now = Instant.now();
        var batchId = UUID.randomUUID().toString();
        for (var row : rows) {
            var before = row.lifecycleState;
            var payload = (ObjectNode) row.payload.deepCopy();
            var target = switch (request.action()) {
                case "archive" -> "archived";
                case "trash" -> "trashed";
                default -> before.equals("trashed") && payload.path("lifecyclePreviousState").asText().equals("archived") ? "archived" : "active";
            };
            if (request.action().equals("trash")) payload.put("lifecyclePreviousState", before);
            else payload.remove("lifecyclePreviousState");
            payload.put("lifecycleChangedAt", now.toString());
            payload.put("lifecycleChangedBy", principal.displayName());
            payload.put("lifecycleChangedAccount", principal.account());
            payload.put("lifecycleReason", reason);
            var revision = payload.withArray("revisions").addObject();
            revision.put("id", UUID.randomUUID().toString()); revision.put("changedAt", now.toString());
            revision.put("editorName", principal.displayName()); revision.put("editorAccount", principal.account());
            revision.put("field", "lifecycleState"); revision.put("before", before); revision.put("after", target);
            revision.put("reason", reason);
            payload.put("updatedAt", now.toString());
            row.lifecycleState = target; row.payload = payload; row.updatedAt = now;
            records.saveAndFlush(row);
            audit.record("quotation." + request.action(), "quotation", row.id.toString(), "success",
                    Map.of("batchId", batchId, "quoteNo", row.quoteNo, "before", before, "after", target, "reason", reason));
        }
        return ApiResponse.ok(new Result(rows.size()));
    }

    private boolean protectedRecord(QuotationRecordEntity row) {
        JsonNode payload = row.payload;
        var review = reviews.currentStatus(row);
        return row.status.equals("won") || payload.path("dealLines").size() > 0
                || payload.path("dealQuantity").asDouble() > 0
                || !review.isBlank() && !review.equals("pending");
    }
    static void assertActive(QuotationRecordEntity row) {
        if (!row.lifecycleState.equals("active")) throw AppException.conflict("报价已归档或移入回收站，请恢复后再修改");
    }
}
