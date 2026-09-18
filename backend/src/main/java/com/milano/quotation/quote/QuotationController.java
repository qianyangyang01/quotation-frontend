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
            "actualQuoteUsd", "actualQuoteCny", "dealQuantity", "closedAt", "note", "customerName", "customerQuote", "quoteConfirmed");
    private final QuotationRecordRepository records;
    private final QuotationRecordQuery recordQuery;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final QuotationReadinessService readiness;
    private final QuotationSubmissionValidator submissionValidator;
    private final com.milano.quotation.logistics.LogisticsQuotationGuard logisticsGuard;

    public QuotationController(QuotationRecordRepository records, AuditService audit,
                               IdempotencyService idempotency, QuotationReadinessService readiness, QuotationRecordQuery recordQuery,
                               QuotationSubmissionValidator submissionValidator, com.milano.quotation.logistics.LogisticsQuotationGuard logisticsGuard) {
        this.recordQuery=recordQuery; this.records = records; this.audit = audit; this.idempotency = idempotency; this.readiness = readiness;
        this.submissionValidator = submissionValidator; this.logisticsGuard=logisticsGuard;
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


    @GetMapping("/search")
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    ApiResponse<QuotationRecordQuery.Result> search(@RequestParam(defaultValue="mine") String scope,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="10") int size,
        @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String status,
        @RequestParam(defaultValue="") String country, @RequestParam(defaultValue="") String category,
        @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate endDate, Authentication auth) {
        return ApiResponse.ok(recordQuery.search(hasAll(auth)&&scope.equals("company")?null:principal(auth).account(),new QuotationRecordQuery.Filters(q,status,country,category,startDate,endDate),page,size));
    }
    /** Poll only visible records; enforce owner scope on the server. */
    @GetMapping("/review-status")
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional(readOnly=true)
    ApiResponse<List<JsonNode>> reviewStatus(@RequestParam List<UUID> ids, Authentication auth) {
        if (ids.size() > 100) throw AppException.unprocessable("一次最多查询100条记录");
        var owner = principal(auth).account();
        var result = new ArrayList<JsonNode>();
        for (var row : records.findAllById(ids)) {
            if (!hasAll(auth) && !row.ownerAccount.equals(owner)) continue;
            var value = JsonNodeFactory.instance.objectNode().put("id", row.id.toString()).put("_version", row.version);
            value.put("financeReviewStatus", row.payload.path("financeReviewStatus").asText("pending"));
            for (var field : QuotationFinanceReview.FIELDS) if (row.payload.has(field)) value.set(field, row.payload.get(field));
            result.add(value);
        }
        return ApiResponse.ok(result);
    }

    @PatchMapping("/{id}/finance-review")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE') and hasAuthority('PERM_allRecords')")
    @Transactional
    ApiResponse<JsonNode> review(@PathVariable UUID id, @RequestBody ObjectNode patch, Authentication auth) {
        var fields = new HashSet<String>(); patch.properties().forEach(entry -> fields.add(entry.getKey()));
        if (!Set.of("_version", "financeReviewStatus").containsAll(fields)
                || !patch.path("_version").isIntegralNumber()
                || !patch.path("financeReviewStatus").isTextual()
                || !QuotationFinanceReview.STATUSES.contains(patch.path("financeReviewStatus").asText()))
            throw AppException.unprocessable("审核状态或记录版本不合法");
        var row = records.findById(id).orElseThrow(() -> AppException.notFound("报价记录不存在"));
        assertVersion(row, patch.path("_version").asLong(-1));
        var current = (ObjectNode) row.payload.deepCopy();
        var target = patch.path("financeReviewStatus").asText();
        if (current.path("financeReviewStatus").asText("pending").equals(target)) return ApiResponse.ok(view(row));
        var now = Instant.now(); var reviewer = principal(auth);
        revision(current.withArray("revisions"), reviewer, "financeReviewStatus", JsonNodeFactory.instance.textNode(current.path("financeReviewStatus").asText("pending")), JsonNodeFactory.instance.textNode(target), now);
        current.put("financeReviewStatus", target);
        current.put("financeReviewedAt", now.toString()); current.put("financeReviewedBy", reviewer.displayName());
        current.put("financeReviewedAccount", reviewer.account()); current.put("updatedAt", now.toString());
        row.payload = current; row.updatedAt = now; records.saveAndFlush(row);
        audit.record("quotation.finance-review", "quotation", id.toString(), "success", Map.of("status", target));
        return ApiResponse.ok(view(row));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional(readOnly=true)
    ApiResponse<JsonNode> get(@PathVariable UUID id, Authentication auth) {
        var row=records.findById(id).orElseThrow(()->AppException.notFound("报价记录不存在"));
        if(!hasAll(auth)&&!row.ownerAccount.equals(principal(auth).account())) throw new org.springframework.security.access.AccessDeniedException("forbidden");
        return ApiResponse.ok(view(row));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_quote')")
    @Transactional
    ApiResponse<JsonNode> create(@RequestBody JsonNode body, @RequestHeader("Idempotency-Key") String key,
                                 Authentication auth) {
        if (!(body instanceof ObjectNode input) || body.toString().length() > 4_000_000) throw AppException.unprocessable("报价数据格式错误或过大");
        submissionValidator.validate(input);
        var principal = principal(auth); var existing = idempotency.existing(principal.account(), "quotation-create", key, body);
        if (existing.isPresent()) return ApiResponse.ok(existing.get());
        submissionValidator.validateQuotePricing(input);
        readiness.assertCanCreate(input);
        var now = Instant.now(); var id = UUID.randomUUID(); var no = quoteNo(now, id); var payload = input.deepCopy();
        logisticsGuard.validate(payload);
        payload.remove("customerId");
        payload.put("id", id.toString()); payload.put("no", no); payload.put("salespersonName", principal.displayName());
        payload.put("salespersonAccount", principal.account()); payload.put("status", "pending");
        payload.put("createdAt", now.toString()); payload.put("updatedAt", now.toString());
        CustomerQuotePrices.initialize(payload);
        QuotationFinanceReview.initialize(payload);
        payload.put("quoteConfirmed", false); payload.remove("quoteConfirmedAt"); payload.remove("quoteConfirmedBy");
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
        submissionValidator.validateUpdate(patch);
        QuotationFinanceReview.rejectDirectPatch(patch);
        var current = (ObjectNode) row.payload.deepCopy(); current.remove("customerId"); var revisions = current.withArray("revisions"); var now = Instant.now();
        CustomerQuotePrices.preparePatch(current, patch);
        QuotationConfirmation.prepare(current, patch);
        if (QuotationFinanceReview.pricesChanged(current, patch) && !current.path("financeReviewStatus").asText("pending").equals("pending")) {
            revision(revisions, principal(auth), "financeReviewStatus", current.get("financeReviewStatus"), JsonNodeFactory.instance.textNode("pending"), now);
            QuotationFinanceReview.initialize(current);
        }
        var wasConfirmed = current.path("quoteConfirmed").asBoolean(false);
        for (var option : current.path("quoteOptions")) {
            if (patch.hasNonNull("dealOptionId") && option.path("id").asText().equals(patch.path("dealOptionId").asText()) && option.path("available").isBoolean() && !option.path("available").asBoolean())
                throw AppException.unprocessable("不可用报价方案不能回填成交");
        }
        for (var line : patch.path("dealLines")) for (var option : current.path("quoteOptions")) {
            if (option.path("id").asText().equals(line.path("optionId").asText()) && option.path("available").isBoolean() && !option.path("available").asBoolean())
                throw AppException.unprocessable("不可用报价方案不能回填成交");
        }
        patch.properties().forEach(entry -> {
            if (PATCH_FIELDS.contains(entry.getKey())) {
                var old = current.get(entry.getKey());
                if (!Objects.equals(old, entry.getValue())) {
                    revision(revisions, principal(auth), entry.getKey(), old, entry.getValue(), now);
                    current.set(entry.getKey(), entry.getValue());
                }
            }
        });
        if (current.path("quoteConfirmed").asBoolean(false) && !wasConfirmed) {
            current.put("quoteConfirmedAt", now.toString()); current.put("quoteConfirmedBy", principal(auth).displayName());
        } else if (!current.path("quoteConfirmed").asBoolean(false)) {
            current.remove("quoteConfirmedAt"); current.remove("quoteConfirmedBy");
        }
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
