package com.milano.quotation.purchase;

import com.milano.quotation.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/purchase-shimo-sync")
@PreAuthorize("hasAuthority('PERM_purchase')")
public class ShimoSyncController {
    private final ShimoSyncService service;
    public ShimoSyncController(ShimoSyncService service) { this.service=service; }
    @GetMapping ApiResponse<Map<String,Object>> status() { return ApiResponse.ok(service.status()); }
    @GetMapping("/items") ApiResponse<Map<String,Object>> items(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="true") boolean pending) { return ApiResponse.ok(service.items(page,pending)); }
    @GetMapping("/changes") ApiResponse<Map<String,Object>> changes(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="false") boolean weightOnly) { return ApiResponse.ok(service.changes(page,weightOnly)); }
    @PostMapping("/enabled") @PreAuthorize("hasRole('SUPER_ADMIN')")
    ApiResponse<Void> enabled(@RequestBody Enable input) { service.setEnabled(input.enabled());return ApiResponse.ok(null); }
    @PostMapping("/run") @PreAuthorize("hasRole('SUPER_ADMIN')")
    ApiResponse<Map<String,Boolean>> run() { return ApiResponse.ok(Map.of("accepted",service.requestRun())); }
    record Enable(boolean enabled) {}
    @PostMapping("/runs/{id}/rollback-preview") @PreAuthorize("hasRole('SUPER_ADMIN')")
    ApiResponse<Map<String,Object>> rollbackPreview(@PathVariable java.util.UUID id) {return ApiResponse.ok(service.rollback(id,true));}
    @PostMapping("/runs/{id}/rollback") @PreAuthorize("hasRole('SUPER_ADMIN')")
    ApiResponse<Map<String,Object>> rollback(@PathVariable java.util.UUID id) {return ApiResponse.ok(service.rollback(id,false));}
}
