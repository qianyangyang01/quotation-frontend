package com.milano.quotation.quote;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.*;
import com.milano.quotation.idempotency.IdempotencyService;
import com.milano.quotation.security.QuotationPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/v1/quotations")
public class QuotationController {
    private static final Set<String> PATCH_FIELDS = Set.of("status", "dealLines", "dealOptionId", "dealOptionLabel",
            "actualQuoteUsd", "actualQuoteCny", "dealQuantity", "closedAt", "note", "customerName");
    private final QuotationRecordRepository records;
    private final QuotationShareRepository shares;
    private final QuotationDocumentService documents;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final QuotationReadinessService readiness;

    public QuotationController(QuotationRecordRepository records, QuotationShareRepository shares,
                               QuotationDocumentService documents, AuditService audit,
                               IdempotencyService idempotency, QuotationReadinessService readiness) {
        this.records = records; this.shares = shares; this.documents = documents;
        this.audit = audit; this.idempotency = idempotency; this.readiness = readiness;
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
        var row = owned(id, auth); assertVersion(row, patch.path("_version").asLong(-1));
        if (row.voidedAt != null) throw AppException.conflict("已作废报价需先恢复后才能修改");
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

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional
    ApiResponse<JsonNode> voidQuotation(@PathVariable UUID id, @Valid @RequestBody VoidInput input, Authentication auth) {
        var row = owned(id, auth); assertVersion(row, input.version());
        if (row.voidedAt != null) throw AppException.conflict("报价已经作废");
        var now = Instant.now(); var payload = (ObjectNode) row.payload.deepCopy();
        payload.put("_statusBeforeVoid", row.status); payload.put("status", "voided"); payload.put("updatedAt", now.toString());
        row.status = "voided"; row.voidedAt = now; row.voidedBy = principal(auth).account(); row.voidReason = input.reason().trim();
        row.updatedAt = now; row.payload = payload;
        records.saveAndFlush(row); audit.record("quotation.void", "quotation", id.toString(), "success", Map.of("reason", row.voidReason));
        return ApiResponse.ok(view(row));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional
    ApiResponse<JsonNode> restore(@PathVariable UUID id, @RequestBody VersionInput input, Authentication auth) {
        var row = owned(id, auth); assertVersion(row, input.version());
        if (row.voidedAt == null) throw AppException.conflict("报价当前不是作废状态");
        var now = Instant.now(); var payload = (ObjectNode) row.payload.deepCopy();
        var previous = payload.path("_statusBeforeVoid").asText("pending"); payload.remove("_statusBeforeVoid");
        payload.put("status", previous); payload.put("updatedAt", now.toString());
        row.status = previous; row.voidedAt = null; row.voidedBy = null; row.voidReason = null; row.updatedAt = now; row.payload = payload;
        records.saveAndFlush(row); audit.record("quotation.restore", "quotation", id.toString(), "success", Map.of());
        return ApiResponse.ok(view(row));
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> pdf(@PathVariable UUID id, Authentication auth) {
        var row = owned(id, auth); var bytes = documents.pdf(row);
        audit.record("quotation.pdf", "quotation", id.toString(), "success", Map.of("bytes", bytes.length));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + row.quoteNo + ".pdf\"")
                .cacheControl(CacheControl.noStore()).body(bytes);
    }

    @PostMapping("/{id}/shares")
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional
    ApiResponse<ShareCreated> share(@PathVariable UUID id, @Valid @RequestBody ShareInput input, Authentication auth) {
        var row = owned(id, auth); var token = token(); var now = Instant.now(); var share = new QuotationShareEntity();
        share.id = UUID.randomUUID(); share.quotationId = row.id; share.tokenHash = sha256(token);
        share.createdBy = principal(auth).account(); share.createdAt = now; share.expiresAt = now.plus(Duration.ofDays(input.days()));
        shares.save(share); audit.record("quotation.share-create", "quotation-share", share.id.toString(), "success", Map.of("quotationId", id));
        return ApiResponse.ok(new ShareCreated(share.id, token, "/share/" + token, share.expiresAt));
    }

    @DeleteMapping("/{id}/shares/{shareId}")
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional
    ApiResponse<Void> revoke(@PathVariable UUID id, @PathVariable UUID shareId, Authentication auth) {
        owned(id, auth); var share = shares.findById(shareId).orElseThrow(() -> AppException.notFound("分享不存在"));
        if (!share.quotationId.equals(id)) throw AppException.notFound("分享不存在");
        share.revokedAt = Instant.now(); audit.record("quotation.share-revoke", "quotation-share", shareId.toString(), "success", Map.of());
        return ApiResponse.ok(null);
    }

    private QuotationRecordEntity owned(UUID id, Authentication auth) {
        var row = records.findById(id).orElseThrow(() -> AppException.notFound("报价记录不存在"));
        if (!hasAll(auth) && !row.ownerAccount.equals(principal(auth).account())) throw new org.springframework.security.access.AccessDeniedException("forbidden");
        return row;
    }
    private static void assertVersion(QuotationRecordEntity row, long expected) { if (expected != row.version) throw AppException.conflict("报价记录已被其他用户修改，请刷新后重试"); }
    private JsonNode view(QuotationRecordEntity row) { var payload = (ObjectNode) row.payload.deepCopy(); payload.put("_version", row.version); payload.put("voidedAt", row.voidedAt == null ? "" : row.voidedAt.toString()); payload.put("voidedBy", Objects.toString(row.voidedBy, "")); payload.put("voidReason", Objects.toString(row.voidReason, "")); return payload; }
    private static void revision(ArrayNode revisions, QuotationPrincipal principal, String field, JsonNode before, JsonNode after, Instant now) { var revision = revisions.addObject(); revision.put("id", UUID.randomUUID().toString()); revision.put("changedAt", now.toString()); revision.put("editorName", principal.displayName()); revision.put("editorAccount", principal.account()); revision.put("field", field); revision.set("before", before == null ? NullNode.instance : before); revision.set("after", after); }
    private static boolean hasAll(Authentication auth) { return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("PERM_allRecords")); }
    private static QuotationPrincipal principal(Authentication auth) { return (QuotationPrincipal) auth.getPrincipal(); }
    private static String token() { var bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); } }
    private static String quoteNo(Instant now, UUID id) { return "QT" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(now) + id.toString().substring(0, 6).toUpperCase(Locale.ROOT); }

    record VoidInput(@NotBlank @Size(min = 2, max = 500) String reason, long version) {}
    record VersionInput(long version) {}
    record ShareInput(@Min(1) @Max(30) int days) {}
    record ShareCreated(UUID id, String token, String path, Instant expiresAt) {}
}
