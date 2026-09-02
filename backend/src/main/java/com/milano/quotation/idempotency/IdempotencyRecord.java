package com.milano.quotation.idempotency;

import tools.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="idempotency_record",uniqueConstraints=@UniqueConstraint(columnNames={"account","operation","idempotency_key"}))
public class IdempotencyRecord {
    @Id public UUID id; @Column(nullable=false,length=24) public String account; @Column(nullable=false,length=96) public String operation;
    @Column(name="idempotency_key",nullable=false,length=120) public String idempotencyKey; @Column(name="request_hash",nullable=false,length=64) public String requestHash;
    @Column(name="response_status",nullable=false) public int responseStatus;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="response_body",nullable=false,columnDefinition="jsonb") public JsonNode responseBody;
    @Column(name="created_at",nullable=false) public Instant createdAt; protected IdempotencyRecord(){}
}
