package com.milano.quotation.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface CustomerRepository extends JpaRepository<Customer, UUID> {
    boolean existsByCodeIgnoreCase(String code);
    Page<Customer> findByEnabledAndNameContainingIgnoreCaseOrEnabledAndCodeContainingIgnoreCase(
            boolean enabled, String name, boolean sameEnabled, String code, Pageable pageable);
    Page<Customer> findByNameContainingIgnoreCaseOrCodeContainingIgnoreCase(String name, String code, Pageable pageable);
}
