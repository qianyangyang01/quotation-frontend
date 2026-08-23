package com.milano.quotation.quote;

import com.milano.quotation.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/quotation-readiness")
public class QuotationReadinessController {
    private final QuotationReadinessService readiness;

    public QuotationReadinessController(QuotationReadinessService readiness) { this.readiness = readiness; }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_quote')")
    ApiResponse<JsonNode> get() { return ApiResponse.ok(readiness.snapshot()); }
}
