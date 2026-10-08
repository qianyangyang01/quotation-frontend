package com.milano.quotation.fob;

import com.milano.quotation.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.List;

@RestController
@RequestMapping("/api/v1/fob-purchase-products")
public class FobPurchaseController {
    private final FobPurchaseService service;
    public FobPurchaseController(FobPurchaseService service) { this.service = service; }
    @PostMapping("/paste/preview") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<FobPurchaseService.Preview> preview(@RequestBody List<JsonNode> input) { return ApiResponse.ok(service.preview(input)); }
    @PostMapping("/paste/confirm") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<FobPurchaseService.Result> confirm(@RequestBody FobPurchaseService.Confirmation input) { return ApiResponse.ok(service.confirm(input)); }
    @GetMapping("/{sku}") @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('PERM_quote')")
    public ApiResponse<JsonNode> get(@PathVariable String sku) { return ApiResponse.ok(service.get(sku)); }
    @GetMapping("/{sku}/history") @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<List<FobPurchaseService.History>> history(@PathVariable String sku) { return ApiResponse.ok(service.history(sku)); }
}
