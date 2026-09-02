package com.milano.quotation.audit;

import com.milano.quotation.common.ApiResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-logs")
@PreAuthorize("hasAuthority('PERM_permissions')")
public class AuditController {
    private final AuditLogRepository logs;
    public AuditController(AuditLogRepository logs) { this.logs = logs; }
    @GetMapping ApiResponse<?> list(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) {
        return ApiResponse.ok(logs.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size)))));
    }
}
