package com.milano.quotation.security;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MustChangePasswordFilterTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private QuotationPrincipal principal(boolean enabled, String role, String password) { return new QuotationPrincipal(UUID.fromString("11111111-1111-4111-8111-111111111111"),"TEST","测试",password,role,enabled,false,role.equals("employee")?List.of("quote"):List.of("permissions")); }
    @Test void revokesDisabledOrPasswordChangedSessions() throws Exception {
        for (var current : List.of(principal(false,"super_admin","old"),principal(true,"super_admin","new"))) {
            var users=mock(UserAccountService.class); when(users.loadUserByUsername("TEST")).thenReturn(current);
            var prior=principal(true,"super_admin","old"); SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(prior,null,prior.getAuthorities()));
            var req=new MockHttpServletRequest("GET","/api/v1/users"); req.getSession(); var res=new MockHttpServletResponse();
            new MustChangePasswordFilter(new JsonMapper(),users).doFilter(req,res,(a,b)->fail("Revoked session reached controller"));
            assertEquals(401,res.getStatus()); assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }
    @Test void refreshesRoleBeforeControllerAuthorization() throws Exception {
        var users=mock(UserAccountService.class); var current=principal(true,"employee","old"); when(users.loadUserByUsername("TEST")).thenReturn(current);
        var prior=principal(true,"super_admin","old"); SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(prior,null,prior.getAuthorities()));
        new MustChangePasswordFilter(new JsonMapper(),users).doFilter(new MockHttpServletRequest("GET","/api/v1/users"),new MockHttpServletResponse(),(a,b)->assertEquals(current,SecurityContextHolder.getContext().getAuthentication().getPrincipal()));
    }
}
