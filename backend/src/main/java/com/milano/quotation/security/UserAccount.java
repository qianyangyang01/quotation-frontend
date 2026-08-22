package com.milano.quotation.security;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class UserAccount {
    @Id public UUID id;
    @Column(nullable = false, unique = true, length = 24) public String account;
    @Column(name = "display_name", nullable = false, length = 80) public String displayName;
    @Column(name = "password_hash", nullable = false, length = 120) public String passwordHash;
    @Column(name = "role_key", nullable = false, length = 32) public String roleKey;
    @Column(nullable = false, length = 16) public String status;
    @Column(name = "must_change_password", nullable = false) public boolean mustChangePassword;
    @Column(name = "password_updated_at", nullable = false) public Instant passwordUpdatedAt;
    @Version public long version;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;

    protected UserAccount() {}

    public static UserAccount create(String account, String name, String hash, String roleKey, boolean mustChangePassword) {
        var now = Instant.now();
        var user = new UserAccount();
        user.id = UUID.randomUUID(); user.account = account; user.displayName = name; user.passwordHash = hash;
        user.roleKey = roleKey; user.status = "enabled"; user.mustChangePassword = mustChangePassword;
        user.passwordUpdatedAt = now; user.createdAt = now; user.updatedAt = now;
        return user;
    }
}
