package com.milano.quotation.purchase;

import com.milano.quotation.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/purchase-rankings")
public class PurchaseRankingController {
    private final PurchaseRankingService rankings;
    public PurchaseRankingController(PurchaseRankingService rankings) {this.rankings=rankings;}
    @GetMapping @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('PERM_purchase')")
    public ApiResponse<PurchaseRankingService.Result> get(@RequestParam(defaultValue="all") String scope,
            @RequestParam(defaultValue="30") int days,@RequestParam(defaultValue="all") String part,
            @RequestParam(defaultValue="false") boolean refresh,Authentication auth) {
        boolean fob=auth.getAuthorities().stream().anyMatch(a->Set.of("ROLE_SUPER_ADMIN","ROLE_PURCHASE","PERM_quote").contains(a.getAuthority()));
        // Scope follows existing catalog access; there is no user-facing source filter.
        return ApiResponse.ok(rankings.get(scope,fob?"all":"standard",days,part,refresh));
    }
}
