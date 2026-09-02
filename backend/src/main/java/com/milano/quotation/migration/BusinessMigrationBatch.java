package com.milano.quotation.migration;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "business_migration_batch")
class BusinessMigrationBatch {
    @Id UUID id;
    @Column(name = "source_origin", nullable = false, length = 255) String sourceOrigin;
    @Column(name = "source_hash", nullable = false, unique = true, length = 64) String sourceHash;
    @Column(name = "source_type", nullable = false, length = 40) String sourceType;
    @Column(nullable = false, length = 24) String status;
    @Column(name = "requested_by", nullable = false, length = 24) String requestedBy;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") JsonNode counts;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") JsonNode report;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") JsonNode diff;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") JsonNode errors;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") JsonNode checkpoint;
    @Column(name = "request_id", length = 64) String requestId;
    @Column(name = "last_error", length = 1000) String lastError;
    @Version long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "completed_at") Instant completedAt;

    protected BusinessMigrationBatch() {}
}
