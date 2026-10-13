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
        return change(request, auth, null);
    }

    @PostMapping("/batch/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional
    public ApiResponse<Result> trashBatch(@PathVariable UUID id, @RequestBody Request request, Authentication auth) {
        if (!"trash".equals(request.action())) throw AppException.unprocessable("整批操作仅支持移入回收站");
        return change(request, auth, id);
    }

    private ApiResponse<Result> change(Request request, Authentication auth, UUID batchMemberId) {
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
            // Keep skipped members locked and version-checked so a concurrent restore cannot
            // silently change the confirmed scope. They are removed only after full batch validation.
            if (batchMemberId != null && Set.of("withdrawn", "trashed").contains(row.lifecycleState)) {
                rows.add(row);
                continue;
            }
            if (row.lifecycleState.equals("withdrawn")) throw AppException.conflict("报价已撤回，请从报价草稿继续编辑");
            if (request.action().equals("restore")) {
                if (row.lifecycleState.equals("active")) throw AppException.conflict("所选记录已在当前记录中，请刷新");
                // Employees cannot undo an administrator's disposition of their records.
                if (!admin && !row.payload.path("lifecycleChangedAccount").asText().equals(principal.account()))
                    throw new AccessDeniedException("请由管理员恢复此记录");
            } else {
                if (row.lifecycleState.equals("trashed") || request.action().equals("archive") && !row.lifecycleState.equals("active"))
                    throw AppException.conflict("所选记录状态已变化，请刷新");
                if (protectedRecord(row)) throw AppException.conflict(row.quoteNo + "：已成交、已有成交明细或审核中/已审核，本次整组未执行；请管理员单独核实");
            }
            rows.add(row);
        }
        // All quote locks are acquired in UUID order, shared with review writes. Never trust
        // the browser's visible page as the complete batch, or load stale entities before locking.
        if (batchMemberId != null) {
            var source = rows.stream().filter(row -> row.id.equals(batchMemberId)).findFirst()
                    .orElseThrow(() -> AppException.conflict("本批记录不完整，本次未执行，请重新打开确认"));
            var batch = source.payload.path("quotationBatch");
            var id = batch.path("id").asText();
            int count = batch.path("count").asInt();
            // Keep original count/index as historical metadata; cancelled members no longer exist.
            // The database membership check below still requires EVERY surviving record.
            if (id.isBlank() || count < rows.size()) throw AppException.conflict("本批记录不完整，本次未执行，请重新打开确认");
            var members = records.findBatch(id, source.ownerAccount);
            var indices = new HashSet<Integer>();
            if (members.size() != rows.size() || !versions.keySet().equals(new HashSet<>(members.stream().map(row -> row.id).toList())))
                throw AppException.conflict("本批记录已变化，本次未执行，请重新打开确认");
            for (var row : rows) {
                var metadata = row.payload.path("quotationBatch");
                int index = metadata.path("index").asInt();
                if (!source.ownerAccount.equals(row.ownerAccount) || !id.equals(metadata.path("id").asText())
                        || metadata.path("count").asInt() != count || index < 1 || index > count || !indices.add(index))
                    throw AppException.conflict("本批记录不完整，本次未执行，请重新打开确认");
            }
            rows.removeIf(row -> Set.of("withdrawn", "trashed").contains(row.lifecycleState));
            if (rows.isEmpty()) throw AppException.conflict("本批已无可移入回收站的记录，请刷新列表");
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
            if (!target.equals("active")) reviews.clearPriority(row, principal);
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
        if (row.lifecycleState.equals("trashed")) throw AppException.conflict("报价已移入回收站，无法审核或修改，请刷新页面");
        if (!row.lifecycleState.equals("active")) throw AppException.conflict("报价已撤回、归档或移入回收站，请刷新后处理");
    }
}
