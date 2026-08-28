package com.milano.quotation.supplierrecord;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import com.milano.quotation.common.AppException;
import com.milano.quotation.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/supplier-records")
@PreAuthorize("hasAuthority('PERM_purchase')")
public class SupplierRecordController {
    private final SupplierRecordService records;
    private final AuditService audit;

    public SupplierRecordController(SupplierRecordService records, AuditService audit) {
        this.records = records;
        this.audit = audit;
    }

    @GetMapping
    ApiResponse<PageResponse<SupplierRecordView>> list(@RequestParam(defaultValue = "") String query,
                                                       @RequestParam(defaultValue = "") String industryBelt,
                                                       @RequestParam(defaultValue = "") String rating,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "10") int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));
        return ApiResponse.ok(PageResponse.from(records.page(query, industryBelt, rating, pageable)));
    }

    @PostMapping
    ApiResponse<SupplierRecordView> create(@Valid @RequestBody SupplierRecordInput input, Authentication authentication) {
        var result = records.create(input, authentication.getName());
        audit.record("supplier-record.create", "supplier-record", result.id().toString(), "success",
                Map.of("name", result.name()));
        return ApiResponse.ok(result);
    }

    @PutMapping("/{id}")
    ApiResponse<SupplierRecordView> update(@PathVariable UUID id,
                                           @RequestHeader("If-Match") long expectedVersion,
                                           @Valid @RequestBody SupplierRecordInput input,
                                           Authentication authentication) {
        try {
            var result = records.update(id, expectedVersion, input, authentication.getName());
            audit.record("supplier-record.update", "supplier-record", id.toString(), "success",
                    Map.of("name", result.name(), "expectedVersion", expectedVersion));
            return ApiResponse.ok(result);
        } catch (AppException error) {
            audit.recordIndependent("supplier-record.update", "supplier-record", id.toString(), "failure",
                    Map.of("expectedVersion", expectedVersion, "reason", error.getMessage()));
            throw error;
        }
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@PathVariable UUID id, @RequestHeader("If-Match") long expectedVersion) {
        try {
            var deleted = records.delete(id, expectedVersion);
            audit.record("supplier-record.delete", "supplier-record", id.toString(), "success",
                    Map.of("name", deleted.name(), "expectedVersion", expectedVersion));
            return ApiResponse.ok(null);
        } catch (AppException error) {
            audit.recordIndependent("supplier-record.delete", "supplier-record", id.toString(), "failure",
                    Map.of("expectedVersion", expectedVersion, "reason", error.getMessage()));
            throw error;
        }
    }
}
