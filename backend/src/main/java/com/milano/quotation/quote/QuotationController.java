package com.milano.quotation.quote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import com.milano.quotation.idempotency.IdempotencyService;
import com.milano.quotation.security.QuotationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController @RequestMapping("/api/v1/quotations")
public class QuotationController {
    private static final Set<String> PATCH_FIELDS=Set.of("status","dealLines","dealOptionId","dealOptionLabel","actualQuoteUsd","actualQuoteCny","dealQuantity","closedAt","note");
    private final QuotationRecordRepository records; private final AuditService audit; private final IdempotencyService idempotency;
    public QuotationController(QuotationRecordRepository records, AuditService audit, IdempotencyService idempotency){this.records=records;this.audit=audit;this.idempotency=idempotency;}
    @GetMapping @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')") @Transactional(readOnly=true)
    ApiResponse<List<JsonNode>> list(@RequestParam(defaultValue="mine") String scope,Authentication auth){
        var principal=(QuotationPrincipal)auth.getPrincipal(); boolean all=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("PERM_allRecords"));
        var rows=all&&scope.equals("company")?records.findAllByOrderByCreatedAtDesc():records.findByOwnerAccountOrderByCreatedAtDesc(principal.account()); return ApiResponse.ok(rows.stream().map(this::view).toList());
    }
    @PostMapping @PreAuthorize("hasAuthority('PERM_quote')") @Transactional ApiResponse<JsonNode> create(@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,Authentication auth){
        if(!(body instanceof ObjectNode input)||body.toString().length()>4_000_000) throw AppException.unprocessable("报价数据格式错误或过大");
        var principal=(QuotationPrincipal)auth.getPrincipal(); var now=Instant.now(); var id=UUID.randomUUID(); var no=quoteNo(now,id);
        var existing=idempotency.existing(principal.account(),"quotation-create",key,body);if(existing.isPresent())return ApiResponse.ok(existing.get());
        var payload=input.deepCopy(); payload.put("id",id.toString());payload.put("no",no);payload.put("salespersonName",principal.displayName());payload.put("salespersonAccount",principal.account());payload.put("status","pending");payload.put("createdAt",now.toString());payload.put("updatedAt",now.toString()); if(!payload.has("revisions"))payload.putArray("revisions");
        var row=new QuotationRecordEntity();row.id=id;row.quoteNo=no;row.ownerAccount=principal.account();row.status="pending";row.payload=payload;row.createdAt=now;row.updatedAt=now;records.save(row);
        var response=view(row);idempotency.save(principal.account(),"quotation-create",key,body,response);audit.record("quotation.create","quotation",id.toString(),"success",Map.of("quoteNo",no));return ApiResponse.ok(response);
    }
    @PatchMapping("/{id}") @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')") @Transactional ApiResponse<JsonNode> update(@PathVariable UUID id,@RequestBody ObjectNode patch,Authentication auth){
        var row=records.findById(id).orElseThrow(()->AppException.notFound("报价记录不存在"));var principal=(QuotationPrincipal)auth.getPrincipal();boolean all=auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("PERM_allRecords"));
        if(!all&&!row.ownerAccount.equals(principal.account()))throw new org.springframework.security.access.AccessDeniedException("forbidden");
        if(!patch.has("_version")||patch.path("_version").asLong(-1)!=row.version)throw AppException.conflict("报价记录已被其他用户修改，请刷新后重试");
        var current=(ObjectNode)row.payload.deepCopy();var revisions=current.withArray("revisions");var now=Instant.now();
        patch.fields().forEachRemaining(entry->{if(PATCH_FIELDS.contains(entry.getKey())){var old=current.get(entry.getKey());if(!Objects.equals(old,entry.getValue())){var revision=revisions.addObject();revision.put("id",UUID.randomUUID().toString());revision.put("changedAt",now.toString());revision.put("editorName",principal.displayName());revision.put("editorAccount",principal.account());revision.put("field",entry.getKey());revision.set("before",old==null?com.fasterxml.jackson.databind.node.NullNode.instance:old);revision.set("after",entry.getValue());current.set(entry.getKey(),entry.getValue());}}});
        current.put("updatedAt",now.toString());row.status=current.path("status").asText(row.status);row.payload=current;row.updatedAt=now;
        audit.record("quotation.update","quotation",id.toString(),"success",Map.of("status",row.status));return ApiResponse.ok(view(row));
    }
    private JsonNode view(QuotationRecordEntity row){var payload=(ObjectNode)row.payload.deepCopy();payload.put("_version",row.version);return payload;}
    private static String quoteNo(Instant now,UUID id){return "QT"+DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(now)+id.toString().substring(0,6).toUpperCase(Locale.ROOT);}
}
