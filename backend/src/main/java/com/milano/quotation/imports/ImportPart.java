package com.milano.quotation.imports;
import jakarta.persistence.*;import java.time.Instant;import java.util.UUID;
@Entity @Table(name="import_part") public class ImportPart {
    @Id public UUID id; @Column(name="job_id",nullable=false) public UUID jobId;
    @Column(name="part_number",nullable=false) public int partNumber;
    @Column(name="object_key",nullable=false,length=512) public String objectKey;
    @Column(nullable=false,length=64) public String sha256;
    @Column(name="size_bytes",nullable=false) public long sizeBytes;
    @Column(name="processed_bytes",nullable=false) public long processedBytes;
    @Column(name="original_name",nullable=false,length=255) public String originalName;
    @Column(nullable=false,length=24) public String status="uploaded";
    @Column(name="error_message",length=1000) public String errorMessage;
    @Column(name="processed_at") public Instant processedAt;
    @Column(name="created_at",nullable=false) public Instant createdAt;
    protected ImportPart() {}
}
