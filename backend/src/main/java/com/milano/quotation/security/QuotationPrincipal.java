package com.milano.quotation.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public record QuotationPrincipal(UUID id, String account, String displayName, String passwordHash, String roleKey,
                                 boolean enabled, boolean mustChangePassword, List<String> permissions) implements UserDetails {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        var authorities = permissions.stream().map(permission -> new SimpleGrantedAuthority("PERM_" + permission)).toList();
        var combined = new java.util.ArrayList<GrantedAuthority>(authorities);
        combined.add(new SimpleGrantedAuthority("ROLE_" + roleKey.toUpperCase()));
        return combined;
    }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return account; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return enabled; }
}
