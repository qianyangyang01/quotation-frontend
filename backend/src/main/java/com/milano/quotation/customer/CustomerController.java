package com.milano.quotation.customer;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {
    private final CustomerRepository customers;
    private final AuditService audit;

    public CustomerController(CustomerRepository customers, AuditService audit) {
        this.customers = customers;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    ApiResponse<PageResponse<View>> list(@RequestParam(defaultValue = "") String query,
                                         @RequestParam(required = false) Boolean enabled,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        var q = query.trim();
        var result = enabled == null
                ? customers.findByNameContainingIgnoreCaseOrCodeContainingIgnoreCase(q, q, pageable)
                : customers.findByEnabledAndNameContainingIgnoreCaseOrEnabledAndCodeContainingIgnoreCase(enabled, q, enabled, q, pageable);
        return ApiResponse.ok(PageResponse.from(result.map(View::of)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_finance')")
    @Transactional
    ApiResponse<View> create(@Valid @RequestBody Input input) {
        var code = input.code().trim().toUpperCase(Locale.ROOT);
        if (customers.existsByCodeIgnoreCase(code)) throw AppException.conflict("客户编码已存在");
        var now = Instant.now();
        var row = new Customer(); row.id = UUID.randomUUID(); row.code = code; row.createdAt = now;
        apply(row, input); row.updatedAt = now; customers.saveAndFlush(row);
        audit.record("customer.create", "customer", row.id.toString(), "success", Map.of("code", code));
        return ApiResponse.ok(View.of(row));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_finance')")
    @Transactional
    ApiResponse<View> update(@PathVariable UUID id, @RequestHeader("If-Match") long expectedVersion,
                             @Valid @RequestBody Input input) {
        var row = customers.findById(id).orElseThrow(() -> AppException.notFound("客户不存在"));
        if (row.version != expectedVersion) throw AppException.conflict("客户资料已变化，请刷新后重试");
        var code = input.code().trim().toUpperCase(Locale.ROOT);
        if (!row.code.equalsIgnoreCase(code) && customers.existsByCodeIgnoreCase(code)) throw AppException.conflict("客户编码已存在");
        row.code = code; apply(row, input); row.updatedAt = Instant.now(); customers.saveAndFlush(row);
        audit.record("customer.update", "customer", id.toString(), "success", Map.of("code", code));
        return ApiResponse.ok(View.of(row));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('PERM_finance')")
    @Transactional
    ApiResponse<View> status(@PathVariable UUID id, @RequestHeader("If-Match") long expectedVersion,
                             @RequestBody StatusInput input) {
        var row = customers.findById(id).orElseThrow(() -> AppException.notFound("客户不存在"));
        if (row.version != expectedVersion) throw AppException.conflict("客户资料已变化，请刷新后重试");
        row.enabled = input.enabled(); row.updatedAt = Instant.now(); customers.saveAndFlush(row);
        audit.record("customer.status", "customer", id.toString(), "success", Map.of("enabled", row.enabled));
        return ApiResponse.ok(View.of(row));
    }

    private static void apply(Customer row, Input input) {
        row.name = input.name().trim(); row.contactName = trim(input.contactName()); row.phone = trim(input.phone());
        row.email = trim(input.email()); row.countryCode = trim(input.countryCode()).toUpperCase(Locale.ROOT);
        row.grade = trim(input.grade()); row.notes = trim(input.notes()); row.enabled = input.enabled();
    }
    private static String trim(String value) { return value == null ? "" : value.trim(); }

    record Input(@NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 160) String name,
                 @Size(max = 80) String contactName, @Size(max = 40) String phone,
                 @Email @Size(max = 160) String email, @Size(max = 8) String countryCode,
                 @Size(max = 40) String grade, @Size(max = 1000) String notes, boolean enabled) {}
    record StatusInput(boolean enabled) {}
    record View(UUID id, String code, String name, String contactName, String phone, String email,
                String countryCode, String grade, String notes, boolean enabled, long version,
                Instant createdAt, Instant updatedAt) {
        static View of(Customer row) { return new View(row.id, row.code, row.name, row.contactName, row.phone,
                row.email, row.countryCode, row.grade, row.notes, row.enabled, row.version, row.createdAt, row.updatedAt); }
    }
}
