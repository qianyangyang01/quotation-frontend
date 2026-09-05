package com.milano.quotation.security;

import com.milano.quotation.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;

@Component
public class MustChangePasswordFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final UserAccountService users;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public MustChangePasswordFilter(ObjectMapper mapper, UserAccountService users) { this.mapper = mapper; this.users = users; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (request.getRequestURI().startsWith("/api/v1/") && auth != null && auth.getPrincipal() instanceof QuotationPrincipal previous) {
            final QuotationPrincipal current;
            try { current = users.loadUserByUsername(previous.account()); }
            catch (UsernameNotFoundException absent) { expire(request, response); return; }
            // Recheck server state on each request: a saved session must not retain revoked permissions.
            if (!current.enabled() || !current.passwordHash().equals(previous.passwordHash())) { expire(request, response); return; }
            if (!current.equals(previous)) {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(current, null, current.getAuthorities()));
                SecurityContextHolder.setContext(context);
                contexts.saveContext(context, request, response);
            }
            if (current.mustChangePassword() && !request.getRequestURI().startsWith("/api/v1/auth/")) {
                error(response, 428, "PASSWORD_CHANGE_REQUIRED", "首次登录必须修改临时密码"); return;
            }
        }
        chain.doFilter(request, response);
    }

    private void expire(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var session = request.getSession(false); if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        error(response, 401, "SESSION_REVOKED", "账号状态或密码已变更，请重新登录");
    }
    private void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message, List.of()));
    }
}
