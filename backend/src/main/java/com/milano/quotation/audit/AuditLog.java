package com.milano.quotation.audit;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id public UUID id;
    @Column(name="request_id", nullable=false, length=64) public String requestId;
    @Column(name="actor_account", nullable=false, length=24) public String actorAccount;
    @Column(nullable=false, length=96) public String action;
    @Column(name="resource_type", nullable=false, length=64) public String resourceType;
    @Column(name="resource_id", length=120) public String resourceId;
    @Column(nullable=false, length=24) public String outcome;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false, columnDefinition="jsonb") public JsonNode detail;
    @Column(name="ip_address", length=64) public String ipAddress;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    protected AuditLog() {}
}
