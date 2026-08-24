package com.milano.quotation.imports;

import tools.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="import_job")
public class ImportJob {
    @Id public UUID id;
    @Column(name="job_type",nullable=false,length=40) public String jobType;
    @Column(nullable=false,length=24) public String status;
    @Column(length=32) public String phase;
    @Column(name="requested_by",nullable=false,length=24) public String requestedBy;
    @Column(name="source_name",nullable=false,length=255) public String sourceName;
    @Column(name="source_hash",length=64) public String sourceHash;
    @Column(name="source_object_key",length=512) public String sourceObjectKey;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") public JsonNode payload;
    @Column(name="error_message",length=1000) public String errorMessage;
    @Column(name="total_rows",nullable=false) public int totalRows;
    @Column(name="processed_rows",nullable=false) public int processedRows;
    @Column(name="valid_rows",nullable=false) public int validRows;
    @Column(name="error_rows",nullable=false) public int errorRows;
    @Column(name="added_rows",nullable=false) public int addedRows;
    @Column(name="updated_rows",nullable=false) public int updatedRows;
    @Column(name="conflict_rows",nullable=false) public int conflictRows;
    @Column(name="progress_percent",nullable=false) public int progressPercent;
    @Column(name="cancel_requested",nullable=false) public boolean cancelRequested;
    @Column(name="heartbeat_at") public Instant heartbeatAt;
    @Column(name="confirmed_at") public Instant confirmedAt;
    @Column(name="rolled_back_at") public Instant rolledBackAt;
    @Column(name="created_at",nullable=false) public Instant createdAt;
    @Column(name="updated_at",nullable=false) public Instant updatedAt;
    @Column(name="completed_at") public Instant completedAt;
    protected ImportJob() {}
}
