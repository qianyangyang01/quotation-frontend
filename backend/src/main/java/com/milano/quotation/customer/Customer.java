package com.milano.quotation.customer;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer")
class Customer {
    @Id UUID id;
    @Column(nullable = false, unique = true, length = 64) String code;
    @Column(nullable = false, length = 160) String name;
    @Column(name = "contact_name", length = 80) String contactName;
    @Column(length = 40) String phone;
    @Column(length = 160) String email;
    @Column(name = "country_code", length = 8) String countryCode;
    @Column(length = 40) String grade;
    @Column(nullable = false) boolean enabled;
    @Column(length = 1000) String notes;
    @Version long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;

    protected Customer() {}
}
