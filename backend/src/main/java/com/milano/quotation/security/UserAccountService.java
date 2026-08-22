package com.milano.quotation.security;

import com.milano.quotation.common.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class UserAccountService implements UserDetailsService {
    private static final List<String> ROLES = List.of("super_admin", "finance", "logistics", "purchase", "employee");
    private final UserAccountRepository users;
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;

    public UserAccountService(UserAccountRepository users, JdbcClient jdbc, PasswordEncoder encoder) {
        this.users = users; this.jdbc = jdbc; this.encoder = encoder;
    }

    @Override
    @Transactional(readOnly = true)
    public QuotationPrincipal loadUserByUsername(String username) {
        var user = users.findByAccountIgnoreCase(normalizeAccount(username)).orElseThrow(() -> new UsernameNotFoundException("账号或密码错误"));
        var permissions = jdbc.sql("select permission_key from role_permission where role_key = :role order by permission_key")
                .param("role", user.roleKey).query(String.class).list();
        return new QuotationPrincipal(user.id, user.account, user.displayName, user.passwordHash, user.roleKey,
                "enabled".equals(user.status), user.mustChangePassword, permissions);
    }

    @Transactional(readOnly = true)
    public List<UserView> list() { return users.findAllByOrderByAccountAsc().stream().map(UserView::of).toList(); }

    @Transactional
    public UserView create(String account, String name, String password, String role) {
        account = normalizeAccount(account); validateAccount(account); validatePassword(password); validateRole(role);
        if (users.existsByAccountIgnoreCase(account)) throw AppException.conflict("账号已存在");
        return UserView.of(users.save(UserAccount.create(account, cleanName(name), encoder.encode(password), role, true)));
    }

    @Transactional
    public UserView update(UUID id, String role, String status) {
        validateRole(role);
        if (!List.of("enabled", "disabled").contains(status)) throw AppException.unprocessable("账号状态不合法");
        var user = users.findById(id).orElseThrow(() -> AppException.notFound("账号不存在"));
        user.roleKey = role; user.status = status; user.updatedAt = Instant.now();
        return UserView.of(user);
    }

    @Transactional
    public void resetPassword(UUID id, String password, String changedBy) {
        validatePassword(password);
        var user = users.findById(id).orElseThrow(() -> AppException.notFound("账号不存在"));
        user.passwordHash = encoder.encode(password); user.passwordUpdatedAt = Instant.now();
        user.mustChangePassword = true; user.updatedAt = Instant.now();
        recordPasswordChange(user.id, changedBy, "admin-reset");
    }

    @Transactional
    public void changePassword(String account, String currentPassword, String newPassword) {
        validatePassword(newPassword);
        var user = users.findByAccountIgnoreCase(normalizeAccount(account)).orElseThrow(() -> AppException.notFound("账号不存在"));
        if (!encoder.matches(currentPassword, user.passwordHash)) throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENT_PASSWORD_INVALID", "当前密码错误");
        user.passwordHash = encoder.encode(newPassword); user.passwordUpdatedAt = Instant.now();
        user.mustChangePassword = false; user.updatedAt = Instant.now();
        recordPasswordChange(user.id, user.account, "self-change");
    }

    private void recordPasswordChange(UUID userId, String changedBy, String type) {
        jdbc.sql("insert into password_change_history(id,user_id,changed_by,change_type,created_at) values(:id,:user,:actor,:type,:time)")
                .param("id", UUID.randomUUID()).param("user", userId).param("actor", normalizeAccount(changedBy))
                // PostgreSQL's JDBC driver does not infer a SQL type for java.time.Instant.
                // OffsetDateTime maps explicitly to TIMESTAMPTZ and keeps the audit time in UTC.
                .param("type", type).param("time", OffsetDateTime.now(ZoneOffset.UTC)).update();
    }

    public static String normalizeAccount(String account) { return account == null ? "" : account.trim().toUpperCase(Locale.ROOT); }
    private static String cleanName(String name) {
        var result = name == null ? "" : name.trim();
        if (result.isEmpty() || result.length() > 80) throw AppException.unprocessable("姓名不能为空且不能超过80个字符");
        return result;
    }
    private static void validateAccount(String account) {
        if (!account.matches("[A-Z0-9_-]{3,24}")) throw AppException.unprocessable("账号只能使用3到24位字母、数字、下划线或短横线");
    }
    private static void validatePassword(String password) {
        if (password == null || password.length() < 10 || password.length() > 72 || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*"))
            throw AppException.unprocessable("密码需为10到72位并同时包含字母和数字");
    }
    private static void validateRole(String role) { if (!ROLES.contains(role)) throw AppException.unprocessable("角色不存在"); }

    public record UserView(UUID id, String name, String account, String role, String status, boolean mustChangePassword,
                           Instant passwordUpdatedAt, long version) {
        static UserView of(UserAccount user) { return new UserView(user.id, user.displayName, user.account, user.roleKey, user.status, user.mustChangePassword, user.passwordUpdatedAt, user.version); }
    }
}
