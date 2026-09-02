package com.milano.quotation.security;

import com.milano.quotation.audit.AuditService;
import com.milano.quotation.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.HttpStatus;
import com.milano.quotation.common.AppException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final UserAccountService users;
    private final AuditService audit;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager, UserAccountService users, AuditService audit) {
        this.authenticationManager = authenticationManager; this.users = users; this.audit = audit;
    }

    @GetMapping("/csrf") ApiResponse<Map<String, String>> csrf(CsrfToken token) {
        return ApiResponse.ok(Map.of("headerName", token.getHeaderName(), "token", token.getToken()));
    }

    @PostMapping("/login") ApiResponse<UserSession> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        final Authentication auth;
        try { auth = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.account(), body.password())); }
        catch (AuthenticationException error) {
            audit.record("auth.login", "session", UserAccountService.normalizeAccount(body.account()), "failure", Map.of("reason", "invalid-credentials"));
            throw new AppException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误");
        }
        request.getSession(true); request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth); SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        var principal = (QuotationPrincipal) auth.getPrincipal(); audit.record("auth.login", "session", principal.account(), "success", Map.of());
        return ApiResponse.ok(UserSession.of(principal));
    }

    @PostMapping("/logout") ApiResponse<Void> logout(HttpServletRequest request) {
        audit.record("auth.logout", "session", currentAccount(), "success", Map.of());
        var session = request.getSession(false); if (session != null) session.invalidate(); SecurityContextHolder.clearContext(); return ApiResponse.ok(null);
    }

    @GetMapping("/me") ApiResponse<UserSession> me(Authentication authentication) {
        return ApiResponse.ok(UserSession.of((QuotationPrincipal) authentication.getPrincipal()));
    }

    @PostMapping("/change-password") ApiResponse<Void> changePassword(@Valid @RequestBody ChangePassword body, HttpServletRequest request, HttpServletResponse response) {
        users.changePassword(currentAccount(), body.currentPassword(), body.newPassword());
        var principal = users.loadUserByUsername(currentAccount());
        var refreshed = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(refreshed); SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        audit.record("auth.change-password", "user", currentAccount(), "success", Map.of()); return ApiResponse.ok(null);
    }

    private static String currentAccount() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
    public record LoginRequest(@NotBlank String account, @NotBlank String password) {}
    public record ChangePassword(@NotBlank String currentPassword, @NotBlank String newPassword) {}
    public record UserSession(String id, String account, String name, String role, java.util.List<String> permissions, boolean mustChangePassword) {
        static UserSession of(QuotationPrincipal principal) { return new UserSession(principal.id().toString(), principal.account(), principal.displayName(), principal.roleKey(), principal.permissions(), principal.mustChangePassword()); }
    }
}
