package com.milano.quotation.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByAccountIgnoreCase(String account);
    boolean existsByAccountIgnoreCase(String account);
    List<UserAccount> findAllByOrderByAccountAsc();
}
