package com.milano.quotation.quote;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.*;
import com.milano.quotation.idempotency.IdempotencyService;
import com.milano.quotation.security.QuotationPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/v1/quotations")
public class QuotationController {
    private static final Set<String> PATCH_FIELDS = Set.of("status", "dealLines", "dealOptionId", "dealOptionLabel",
            "actualQuoteUsd", "actualQuoteCny", "dealQuantity", "closedAt", "note", "customerName");
    private final QuotationRecordRepository records;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final QuotationReadinessService readiness;
    private final QuotationSubmissionValidator submissionValidator;

    public QuotationController(QuotationRecordRepository records, AuditService audit,
                               IdempotencyService idempotency, QuotationReadinessService readiness,
                               QuotationSubmissionValidator submissionValidator) {
        this.records = records; this.audit = audit; this.idempotency = idempotency; this.readiness = readiness;
        this.submissionValidator = submissionValidator;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional(readOnly = true)
    ApiResponse<PageResponse<JsonNode>> list(@RequestParam(defaultValue = "mine") String scope,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size,
                                             Authentication auth) {
        var principal = principal(auth); var all = hasAll(auth);
        var pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)), Sort.by(Sort.Direction.DESC, "createdAt"));
        var rows = all && scope.equals("company") ? records.findAll(pageable) : records.findByOwnerAccount(principal.account(), pageable);
        return ApiResponse.ok(PageResponse.from(rows.map(this::view)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_quote')")
    @Transactional
    ApiResponse<JsonNode> create(@RequestBody JsonNode body, @RequestHeader("Idempotency-Key") String key,
                                 Authentication auth) {
        if (!(body instanceof ObjectNode input) || body.toString().length() > 4_000_000) throw AppException.unprocessable("报价数据格式错误或过大");
        submissionValidator.validate(input);
        readiness.assertCanCreate(input);
        var principal = principal(auth); var existing = idempotency.existing(principal.account(), "quotation-create", key, body);
        if (existing.isPresent()) return ApiResponse.ok(existing.get());
        var now = Instant.now(); var id = UUID.randomUUID(); var no = quoteNo(now, id); var payload = input.deepCopy();
        payload.remove("customerId");
        payload.put("id", id.toString()); payload.put("no", no); payload.put("salespersonName", principal.displayName());
        payload.put("salespersonAccount", principal.account()); payload.put("status", "pending");
        payload.put("createdAt", now.toString()); payload.put("updatedAt", now.toString());
        if (!payload.has("revisions")) payload.putArray("revisions");
        var row = new QuotationRecordEntity(); row.id = id; row.quoteNo = no; row.ownerAccount = principal.account();
        row.status = "pending";
        row.payload = payload; row.createdAt = now; row.updatedAt = now; records.saveAndFlush(row);
        var response = view(row); idempotency.save(principal.account(), "quotation-create", key, body, response);
        audit.record("quotation.create", "quotation", id.toString(), "success", Map.of("quoteNo", no));
        return ApiResponse.ok(response);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional
    ApiResponse<JsonNode> update(@PathVariable UUID id, @RequestBody ObjectNode patch, Authentication auth) {
        var row = mine(id, auth); assertVersion(row, patch.path("_version").asLong(-1));
        var current = (ObjectNode) row.payload.deepCopy(); current.remove("customerId"); var revisions = current.withArray("revisions"); var now = Instant.now();
        patch.properties().forEach(entry -> {
            if (PATCH_FIELDS.contains(entry.getKey())) {
                var old = current.get(entry.getKey());
                if (!Objects.equals(old, entry.getValue())) {
                    revision(revisions, principal(auth), entry.getKey(), old, entry.getValue(), now);
                    current.set(entry.getKey(), entry.getValue());
                }
            }
        });
        current.put("updatedAt", now.toString()); row.status = current.path("status").asText(row.status);
        row.payload = current; row.updatedAt = now;
        records.saveAndFlush(row); audit.record("quotation.update", "quotation", id.toString(), "success", Map.of("status", row.status));
        return ApiResponse.ok(view(row));
    }

    private QuotationRecordEntity mine(UUID id, Authentication auth) {
        var row = records.findById(id).orElseThrow(() -> AppException.notFound("报价记录不存在"));
        if (!row.ownerAccount.equals(principal(auth).account())) throw new org.springframework.security.access.AccessDeniedException("forbidden");
        return row;
    }
    private static void assertVersion(QuotationRecordEntity row, long expected) { if (expected != row.version) throw AppException.conflict("报价记录已被其他用户修改，请刷新后重试"); }
    private JsonNode view(QuotationRecordEntity row) { var payload = (ObjectNode) row.payload.deepCopy(); payload.put("_version", row.version); return payload; }
    private static void revision(ArrayNode revisions, QuotationPrincipal principal, String field, JsonNode before, JsonNode after, Instant now) { var revision = revisions.addObject(); revision.put("id", UUID.randomUUID().toString()); revision.put("changedAt", now.toString()); revision.put("editorName", principal.displayName()); revision.put("editorAccount", principal.account()); revision.put("field", field); revision.set("before", before == null ? NullNode.instance : before); revision.set("after", after); }
    private static boolean hasAll(Authentication auth) { return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("PERM_allRecords")); }
    private static QuotationPrincipal principal(Authentication auth) { return (QuotationPrincipal) auth.getPrincipal(); }
    private static String quoteNo(Instant now, UUID id) { return "QT" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(now) + id.toString().substring(0, 6).toUpperCase(Locale.ROOT); }
}
