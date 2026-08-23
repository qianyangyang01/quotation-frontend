package com.milano.quotation.quote;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import com.milano.quotation.idempotency.IdempotencyService;
import com.milano.quotation.security.QuotationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController @RequestMapping("/api/v1/quotation-templates") @PreAuthorize("hasAuthority('PERM_quote')")
public class QuotationTemplateController {
    private final QuotationTemplateRepository templates;private final AuditService audit;private final IdempotencyService idempotency;
    public QuotationTemplateController(QuotationTemplateRepository templates,AuditService audit,IdempotencyService idempotency){this.templates=templates;this.audit=audit;this.idempotency=idempotency;}
    @GetMapping @Transactional(readOnly=true) ApiResponse<List<JsonNode>> list(Authentication auth){return ApiResponse.ok(templates.findByOwnerAccountOrderByUpdatedAtDesc(account(auth)).stream().map(this::view).toList());}
    @PostMapping @Transactional ApiResponse<JsonNode> create(@RequestBody ObjectNode body,@RequestHeader("Idempotency-Key")String key,Authentication auth){
        var actor=account(auth);var existing=idempotency.existing(actor,"quotation-template-create",key,body);if(existing.isPresent())return ApiResponse.ok(existing.get());var name=body.path("name").asText("").trim();if(name.isEmpty()||name.length()>120)throw AppException.unprocessable("模板名称不能为空且不能超过120个字符");var now=Instant.now();var row=new QuotationTemplateEntity();row.id=UUID.randomUUID();row.ownerAccount=actor;row.name=name;row.payload=body.deepCopy();row.createdAt=now;row.updatedAt=now;templates.save(row);var response=view(row);idempotency.save(actor,"quotation-template-create",key,body,response);audit.record("template.create","quotation-template",row.id.toString(),"success",Map.of());return ApiResponse.ok(response);}
    @PutMapping("/{id}") @Transactional ApiResponse<JsonNode> update(@PathVariable UUID id,@RequestBody ObjectNode body,Authentication auth){var row=owned(id,auth);if(!body.has("_version")||body.path("_version").asLong(-1)!=row.version)throw AppException.conflict("报价模板已被其他页面修改，请刷新后重试");var merged=(ObjectNode)row.payload.deepCopy();body.properties().forEach(entry->{if(!entry.getKey().equals("_version"))merged.set(entry.getKey(),entry.getValue());});var name=merged.path("name").asText(row.name).trim();if(name.isEmpty()||name.length()>120)throw AppException.unprocessable("模板名称不合法");row.name=name;row.payload=merged;row.updatedAt=Instant.now();templates.saveAndFlush(row);audit.record("template.update","quotation-template",id.toString(),"success",Map.of());return ApiResponse.ok(view(row));}
    @DeleteMapping("/{id}") @Transactional ApiResponse<Void> delete(@PathVariable UUID id,Authentication auth){templates.delete(owned(id,auth));audit.record("template.delete","quotation-template",id.toString(),"success",Map.of());return ApiResponse.ok(null);}
    private QuotationTemplateEntity owned(UUID id,Authentication auth){var row=templates.findById(id).orElseThrow(()->AppException.notFound("报价模板不存在"));if(!row.ownerAccount.equals(account(auth)))throw new org.springframework.security.access.AccessDeniedException("forbidden");return row;}
    private JsonNode view(QuotationTemplateEntity row){var body=(ObjectNode)row.payload.deepCopy();body.put("id",row.id.toString());body.put("ownerAccount",row.ownerAccount);body.put("name",row.name);body.put("createdAt",row.createdAt.toString());body.put("updatedAt",row.updatedAt.toString());body.put("_version",row.version);return body;}
    private static String account(Authentication auth){return ((QuotationPrincipal)auth.getPrincipal()).account();}
}
