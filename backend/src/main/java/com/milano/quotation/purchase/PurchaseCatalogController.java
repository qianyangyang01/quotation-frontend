package com.milano.quotation.purchase;

import com.milano.quotation.common.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/purchase-catalog")
public class PurchaseCatalogController {
    private final PurchaseCatalogService catalog;
    public PurchaseCatalogController(PurchaseCatalogService catalog) {this.catalog=catalog;}
    @GetMapping @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('PERM_purchase')")
    public ApiResponse<PageResponse<JsonNode>> list(@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,Authentication auth) {
        boolean includeFob=auth.getAuthorities().stream().anyMatch(a->Set.of("ROLE_SUPER_ADMIN","ROLE_PURCHASE","PERM_quote").contains(a.getAuthority()));
        return ApiResponse.ok(catalog.page(q,Math.max(0,page),Math.max(1,Math.min(size,100)),includeFob));
    }
}
