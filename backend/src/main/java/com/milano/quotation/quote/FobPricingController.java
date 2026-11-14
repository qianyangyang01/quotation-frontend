package com.milano.quotation.quote;
import com.milano.quotation.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@RestController @RequestMapping("/api/v1/fob-pricing")
public class FobPricingController {
    private final FobQuotation pricing;
    public FobPricingController(FobQuotation pricing){this.pricing=pricing;}
    @GetMapping("/{sku}") @PreAuthorize("hasAuthority('PERM_quote')") @Transactional
    ApiResponse<JsonNode> get(@PathVariable String sku,@RequestParam String source,@RequestParam String classification){
        return ApiResponse.ok(pricing.classifiedProduct(sku,source,classification));
    }
}
