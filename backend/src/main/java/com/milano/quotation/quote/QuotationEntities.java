package com.milano.quotation.quote;

import tools.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="quotation_record")
class QuotationRecordEntity {
    @Id UUID id; @Column(name="quote_no",nullable=false,unique=true,length=40) String quoteNo;
    @Column(name="owner_account",nullable=false,length=24) String ownerAccount; @Column(nullable=false,length=16) String status;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") JsonNode payload; @Version long version;
    @Column(name="voided_at") Instant voidedAt;
    @Column(name="voided_by",length=24) String voidedBy; @Column(name="void_reason",length=500) String voidReason;
    @Column(name="created_at",nullable=false) Instant createdAt; @Column(name="updated_at",nullable=false) Instant updatedAt;
    protected QuotationRecordEntity() {}
}

@Entity @Table(name="quotation_share")
class QuotationShareEntity {
    @Id UUID id; @Column(name="quotation_id",nullable=false) UUID quotationId;
    @Column(name="token_hash",nullable=false,unique=true,length=64) String tokenHash;
    @Column(name="created_by",nullable=false,length=24) String createdBy;
    @Column(name="expires_at",nullable=false) Instant expiresAt; @Column(name="revoked_at") Instant revokedAt;
    @Column(name="created_at",nullable=false) Instant createdAt; protected QuotationShareEntity() {}
}

@Entity @Table(name="quotation_template")
class QuotationTemplateEntity {
    @Id UUID id; @Column(name="owner_account",nullable=false,length=24) String ownerAccount; @Column(nullable=false,length=120) String name;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") JsonNode payload; @Version long version;
    @Column(name="created_at",nullable=false) Instant createdAt; @Column(name="updated_at",nullable=false) Instant updatedAt;
    protected QuotationTemplateEntity() {}
}

@Entity @Table(name="quotation_draft")
class QuotationDraftEntity {
    @Id @Column(name="owner_account",length=24) String ownerAccount;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") JsonNode payload; @Version long version;
    @Column(name="updated_at",nullable=false) Instant updatedAt; protected QuotationDraftEntity() {}
}
