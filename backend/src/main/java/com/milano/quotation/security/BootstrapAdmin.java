package com.milano.quotation.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapAdmin implements ApplicationRunner {
    private final UserAccountRepository users; private final PasswordEncoder encoder;
    private final String account; private final String name; private final String password;
    public BootstrapAdmin(UserAccountRepository users, PasswordEncoder encoder,
                          @Value("${app.bootstrap-admin.account}") String account,
                          @Value("${app.bootstrap-admin.name}") String name,
                          @Value("${app.bootstrap-admin.password}") String password) {
        this.users = users; this.encoder = encoder; this.account = account; this.name = name; this.password = password;
    }
    @Override @Transactional public void run(ApplicationArguments args) {
        if (users.count() > 0) return;
        if (password == null || password.isBlank()) throw new IllegalStateException("APP_BOOTSTRAP_ADMIN_PASSWORD must be set for an empty quotation database");
        users.save(UserAccount.create(UserAccountService.normalizeAccount(account), name.trim(), encoder.encode(password), "super_admin", true));
    }
}
