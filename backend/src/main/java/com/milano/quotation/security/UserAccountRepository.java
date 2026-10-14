package com.milano.quotation.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByAccountIgnoreCase(String account);
    boolean existsByAccountIgnoreCase(String account);
    List<UserAccount> findAllByOrderByAccountAsc();
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from UserAccount u where u.account in :accounts order by u.account")
    List<UserAccount> lockTemplateSyncAccounts(@org.springframework.data.repository.query.Param("accounts") java.util.Collection<String> accounts);
}
