package com.milano.quotation.security;

import com.milano.quotation.common.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserAccountServiceTest {
    private UserAccountRepository users;
    private PasswordEncoder encoder;
    private JdbcClient jdbc;
    private UserAccountService service;

    @BeforeEach void setUp() {
        users = mock(UserAccountRepository.class);
        encoder = mock(PasswordEncoder.class);
        jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        service = new UserAccountService(users, jdbc, encoder);
    }

    @Test void createsNormalizedQuotationAccountWithForcedPasswordChange() {
        when(users.existsByAccountIgnoreCase("BUYER_01")).thenReturn(false);
        when(encoder.encode("SecurePass123")).thenReturn("bcrypt-hash");
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.create(" buyer_01 ", " 采购员 ", "SecurePass123", "purchase");
        assertEquals("BUYER_01", result.account());
        assertTrue(result.mustChangePassword());
        var entity = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).save(entity.capture());
        assertEquals("bcrypt-hash", entity.getValue().passwordHash);
    }

    @Test void rejectsDuplicateAndWeakCredentials() {
        when(users.existsByAccountIgnoreCase("EXISTING")).thenReturn(true);
        assertEquals("CONFLICT", assertThrows(AppException.class, () -> service.create("existing", "用户", "SecurePass123", "employee")).code());
        assertEquals("VALIDATION_ERROR", assertThrows(AppException.class, () -> service.create("new_user", "用户", "weak", "employee")).code());
    }

    @Test void changingPasswordChecksCurrentHashAndClearsFirstLoginFlag() {
        var account = UserAccount.create("EMP001", "员工", "old-hash", "employee", true);
        when(users.findByAccountIgnoreCase("EMP001")).thenReturn(Optional.of(account));
        when(encoder.matches("OldPass123", "old-hash")).thenReturn(true);
        when(encoder.encode("NewPass4567")).thenReturn("new-hash");
        service.changePassword("emp001", "OldPass123", "NewPass4567");
        assertEquals("new-hash", account.passwordHash);
        assertFalse(account.mustChangePassword);
    }

    @Test void protectsCurrentAccountRoleAndStatusButAllowsIdempotentUpdate() {
        var current = UserAccount.create("ADMIN", "管理员", "hash", "super_admin", false);
        when(users.findById(current.id)).thenReturn(Optional.of(current));

        var disable = assertThrows(AppException.class,
                () -> service.update(current.id, "super_admin", "disabled", "admin"));
        assertEquals("CURRENT_ACCOUNT_PROTECTED", disable.code());

        var demote = assertThrows(AppException.class,
                () -> service.update(current.id, "finance", "enabled", "ADMIN"));
        assertEquals("CURRENT_ACCOUNT_PROTECTED", demote.code());

        var unchanged = service.update(current.id, "super_admin", "enabled", "ADMIN");
        assertEquals("super_admin", unchanged.role());
        assertEquals("enabled", unchanged.status());
    }

    @Test void allowsAuthorizedActorToUpdateAnotherAccount() {
        var employee = UserAccount.create("EMP001", "员工", "hash", "employee", false);
        when(users.findById(employee.id)).thenReturn(Optional.of(employee));

        var updated = service.update(employee.id, "purchase", "disabled", "ADMIN");

        assertEquals("purchase", updated.role());
        assertEquals("disabled", updated.status());
    }
}
