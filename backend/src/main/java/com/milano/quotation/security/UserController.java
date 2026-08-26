package com.milano.quotation.security;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasAuthority('PERM_permissions')")
public class UserController {
    private final UserAccountService users;
    private final AuditService audit;
    public UserController(UserAccountService users, AuditService audit) { this.users = users; this.audit = audit; }

    @GetMapping ApiResponse<List<UserAccountService.UserView>> list() { return ApiResponse.ok(users.list()); }
    @PostMapping ApiResponse<UserAccountService.UserView> create(@Valid @RequestBody CreateUser body) {
        var result = users.create(body.account(), body.name(), body.password(), body.role());
        audit.record("user.create", "user", result.id().toString(), "success", Map.of("account", result.account(), "role", result.role())); return ApiResponse.ok(result);
    }
    @PatchMapping("/{id}") ApiResponse<UserAccountService.UserView> update(@PathVariable UUID id, @Valid @RequestBody UpdateUser body, Authentication authentication) {
        var actor = UserAccountService.normalizeAccount(authentication.getName());
        try {
            var result = users.update(id, body.role(), body.status(), actor);
            audit.record("user.update", "user", id.toString(), "success", Map.of("actor", actor, "targetAccount", result.account(), "role", body.role(), "status", body.status()));
            return ApiResponse.ok(result);
        } catch (com.milano.quotation.common.AppException error) {
            audit.recordIndependent("user.update", "user", id.toString(), "failure", Map.of("actor", actor, "role", body.role(), "status", body.status(), "reason", error.getMessage()));
            throw error;
        }
    }
    @PostMapping("/{id}/reset-password") ApiResponse<Void> reset(@PathVariable UUID id, @Valid @RequestBody ResetPassword body, Authentication authentication) {
        users.resetPassword(id, body.password(), authentication.getName()); audit.record("user.reset-password", "user", id.toString(), "success", Map.of()); return ApiResponse.ok(null);
    }

    public record CreateUser(@NotBlank String account, @NotBlank String name, @NotBlank String password, @NotBlank String role) {}
    public record UpdateUser(@NotBlank String role, @NotBlank String status) {}
    public record ResetPassword(@NotBlank String password) {}
}
