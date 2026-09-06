package com.milano.quotation.quote;

import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import com.milano.quotation.logistics.LogisticsQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Small, uncached version check. Never loads product payloads or price rows. */
@RestController
@RequestMapping("/api/v1/quotation-sync")
public class QuotationSyncController {
    private final JdbcClient jdbc;
    private final LogisticsQueryService logistics;
    private final com.milano.quotation.logistics.LogisticsQuotationGuard guard;
    public QuotationSyncController(JdbcClient jdbc, LogisticsQueryService logistics,com.milano.quotation.logistics.LogisticsQuotationGuard guard) { this.jdbc=jdbc; this.logistics=logistics;this.guard=guard; }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_quote','PERM_finance')")
    @Transactional(readOnly=true)
    public ResponseEntity<ApiResponse<Snapshot>> snapshot(@RequestParam(defaultValue="") List<String> sku) {
        if(sku.size()>100) throw AppException.unprocessable("一次最多核验100个商品");
        var skus=sku.stream().map(s->s.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""))
                .filter(s->!s.isEmpty()).distinct().sorted().toList();
        if(skus.stream().anyMatch(s->s.length()>96||!s.matches("[A-Z0-9._/-]+"))) throw AppException.unprocessable("SKU格式不合法");
        var products=new LinkedHashMap<String,String>();
        skus.forEach(s->products.put(s,null));
        if(!skus.isEmpty()) jdbc.sql("select sku,version,updated_at from purchase_product where sku in (:skus)")
                .param("skus",skus).query((rs,n)->{
                    products.put(rs.getString("sku"),rs.getLong("version")+":"+rs.getTimestamp("updated_at").toInstant());
                    return true;
                }).list();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(new Snapshot(products,logistics.manifestRevision().revision())));
    }
    @PostMapping("/logistics")
    @PreAuthorize("hasAuthority('PERM_quote')")
    @Transactional
    public ApiResponse<Map<String,String>> checkLogistics(@RequestBody tools.jackson.databind.node.ObjectNode body){
        if(body.toString().length()>500_000)throw AppException.unprocessable("核验数据过大");
        body.put("logisticsSyncScope","selected");
        guard.validate(body);
        return ApiResponse.ok(Map.of("revision",body.path("logisticsRevision").asText()));
    }
    public record Snapshot(Map<String,String> purchaseVersions,String logisticsRevision) {}
}
