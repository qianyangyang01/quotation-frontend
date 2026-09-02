package com.milano.quotation.storage;

import jakarta.persistence.*;
import java.time.Instant;import java.util.UUID;

@Entity @Table(name="asset_object")
public class AssetObject{
    @Id public UUID id;@Column(nullable=false,unique=true,length=64)public String sha256;@Column(name="object_key",nullable=false,unique=true,length=512)public String objectKey;@Column(name="media_type",nullable=false,length=120)public String mediaType;@Column(name="size_bytes",nullable=false)public long sizeBytes;@Column(name="original_name",nullable=false,length=255)public String originalName;@Column(name="storage_state",nullable=false,length=24)public String storageState;@Column(name="staging_job_id")public UUID stagingJobId;@Column(name="expires_at")public Instant expiresAt;@Column(name="created_at",nullable=false)public Instant createdAt;protected AssetObject(){}
}
