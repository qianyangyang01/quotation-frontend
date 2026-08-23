package com.milano.quotation.logistics;

import tools.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="logistics_provider") class LogisticsProviderEntity{
    @Id UUID id;@Column(nullable=false,unique=true,length=64)String code;@JdbcTypeCode(SqlTypes.JSON)@Column(nullable=false,columnDefinition="jsonb")JsonNode payload;@Version long version;@Column(name="created_at",nullable=false)Instant createdAt;@Column(name="updated_at",nullable=false)Instant updatedAt;protected LogisticsProviderEntity(){}
}
@Entity @Table(name="logistics_channel") class LogisticsChannelEntity{
    @Id UUID id;@Column(name="provider_id",nullable=false)UUID providerId;@Column(nullable=false,unique=true,length=96)String code;@Column(name="rule_id",nullable=false,unique=true)int ruleId;@Column(name="current_version_id")UUID currentVersionId;@JdbcTypeCode(SqlTypes.JSON)@Column(nullable=false,columnDefinition="jsonb")JsonNode payload;@Version long version;@Column(name="created_at",nullable=false)Instant createdAt;@Column(name="updated_at",nullable=false)Instant updatedAt;protected LogisticsChannelEntity(){}
}
@Entity @Table(name="logistics_version",uniqueConstraints={@UniqueConstraint(columnNames={"channel_id","version_number"}),@UniqueConstraint(columnNames={"channel_id","source_hash"})}) class LogisticsVersionEntity{
    @Id UUID id;@Column(name="channel_id",nullable=false)UUID channelId;@Column(name="version_number",nullable=false)int versionNumber;@Column(nullable=false,length=24)String status;@Column(name="source_hash",nullable=false,length=128)String sourceHash;@JdbcTypeCode(SqlTypes.JSON)@Column(nullable=false,columnDefinition="jsonb")JsonNode payload;@Column(name="created_at",nullable=false)Instant createdAt;@Column(name="published_at")Instant publishedAt;protected LogisticsVersionEntity(){}
}
