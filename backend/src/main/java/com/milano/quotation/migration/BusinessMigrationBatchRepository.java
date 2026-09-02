package com.milano.quotation.migration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface BusinessMigrationBatchRepository extends JpaRepository<BusinessMigrationBatch, UUID> {
    Optional<BusinessMigrationBatch> findBySourceHash(String sourceHash);
}
