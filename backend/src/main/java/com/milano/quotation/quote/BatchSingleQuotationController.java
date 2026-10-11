package com.milano.quotation.quote;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.*;
import com.milano.quotation.idempotency.IdempotencyService;
import com.milano.quotation.security.QuotationPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/v1/batch-single-quotations")
public class BatchSingleQuotationController {
    private final JdbcClient jdbc; private final JsonMapper mapper; private final BatchSingleQuotation batch;
    private final IdempotencyService idempotency; private final AuditService audit;
    public BatchSingleQuotationController(JdbcClient jdbc, JsonMapper mapper, BatchSingleQuotation batch, IdempotencyService idempotency, AuditService audit) {
        this.jdbc=jdbc; this.mapper=mapper; this.batch=batch; this.idempotency=idempotency; this.audit=audit;
    }
    @PostMapping
    @PreAuthorize("hasAuthority('PERM_quote')")
    @Transactional(timeout=120)
    public ApiResponse<JsonNode> create(@RequestBody JsonNode body, @RequestHeader("Idempotency-Key") String key, Authentication auth) {
        if (body.toString().length()>4_000_000) throw AppException.unprocessable("批量报价数据过大");
        var user=(QuotationPrincipal)auth.getPrincipal();
        var old=idempotency.existing(user.account(),"batch-single-create",key,body);
        if(old.isPresent()) return ApiResponse.ok(old.get());
        var payload=batch.prepare(body); var id=UUID.randomUUID(); var now=Instant.now();
        var no="BQS"+DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Shanghai")).format(now)+id.toString().substring(0,6).toUpperCase(Locale.ROOT);
        payload.put("id",id.toString()).put("no",no).put("createdAt",now.toString()).put("salespersonName",user.displayName());
        jdbc.sql("insert into batch_single_quotation(id,quote_no,owner_account,created_at,payload) values(:id,:no,:owner,:created,cast(:payload as jsonb))")
            .param("id",id).param("no",no).param("owner",user.account()).param("created",java.sql.Timestamp.from(now)).param("payload",payload.toString()).update();
        idempotency.save(user.account(),"batch-single-create",key,body,payload);
        audit.record("batch-single.create","batch-single-quotation",id.toString(),"success",Map.of("quoteNo",no,"skuCount",payload.path("items").size()));
        return ApiResponse.ok(payload);
    }
    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional(readOnly=true)
    public ApiResponse<Map<String,Object>> list(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="mine") String scope, Authentication auth) {
        var user=(QuotationPrincipal)auth.getPrincipal(); boolean all=hasAll(auth)&&"company".equals(scope);
        var rows=jdbc.sql("select jsonb_build_object('id',id,'no',quote_no,'customerName',payload->>'customerName','skuCount',jsonb_array_length(payload->'items'),'createdAt',created_at,'salespersonName',payload->>'salespersonName')::text from batch_single_quotation where (:all or owner_account=:owner) order by created_at desc,id desc limit 20 offset :offset")
            .param("all",all).param("owner",user.account()).param("offset",Math.max(0L,(long)page)*20).query((rs,n)->mapper.readTree(rs.getString(1))).list();
        var total=jdbc.sql("select count(*) from batch_single_quotation where (:all or owner_account=:owner)").param("all",all).param("owner",user.account()).query(Long.class).single();
        return ApiResponse.ok(Map.of("items",rows,"total",total));
    }
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_myRecords','PERM_allRecords')")
    @Transactional(readOnly=true)
    public ApiResponse<JsonNode> get(@PathVariable UUID id, Authentication auth) {
        var user=(QuotationPrincipal)auth.getPrincipal();
        var result=jdbc.sql("select payload::text from batch_single_quotation where id=:id and (:all or owner_account=:owner)").param("id",id).param("all",hasAll(auth)).param("owner",user.account()).query((rs,n)->mapper.readTree(rs.getString(1))).optional().orElseThrow(()->AppException.notFound("批量报价不存在或无权查看"));
        return ApiResponse.ok(result);
    }
    private static boolean hasAll(Authentication auth) { return auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("PERM_allRecords")); }
}
