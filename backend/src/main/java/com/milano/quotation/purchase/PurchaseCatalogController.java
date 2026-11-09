package com.milano.quotation.purchase;

import com.milano.quotation.common.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.Set;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;

@RestController
@RequestMapping("/api/v1/purchase-catalog")
public class PurchaseCatalogController {
    private final PurchaseCatalogService catalog;
    public PurchaseCatalogController(PurchaseCatalogService catalog) {this.catalog=catalog;}
    @GetMapping @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('PERM_purchase')")
    public ApiResponse<PageResponse<JsonNode>> list(@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateTo,Authentication auth) {
        return ApiResponse.ok(catalog.page(q,Math.max(0,page),Math.max(1,Math.min(size,100)),includeFob(auth),dateFrom,dateTo));
    }
    @GetMapping("/export.xlsx") @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('PERM_purchase')")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue="") String q,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateTo,Authentication auth) throws IOException {
        String filename="采购SKU_"+(dateFrom==null?"不限":dateFrom)+"_至_"+(dateTo==null?"不限":dateTo)+".xlsx";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename,StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noStore()).body(catalog.export(q,includeFob(auth),dateFrom,dateTo));
    }
    private boolean includeFob(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a->Set.of("ROLE_SUPER_ADMIN","ROLE_PURCHASE","PERM_quote").contains(a.getAuthority()));
    }
}
