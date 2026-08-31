package com.milano.quotation.imports;
import tools.jackson.databind.JsonNode;import jakarta.persistence.*;import org.hibernate.annotations.JdbcTypeCode;import org.hibernate.type.SqlTypes;import java.time.Instant;import java.util.UUID;
@Entity @Table(name="purchase_import_row") public class PurchaseImportRow {
    @Id public UUID id; @Column(name="job_id",nullable=false) public UUID jobId;
    @Column(name="source_row",nullable=false) public int sourceRow;
    @Column(name="source_sheet",nullable=false,length=128) public String sourceSheet="采购产品导入";
    @Column(nullable=false,length=96) public String sku;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb") public JsonNode payload;
    @Column(name="validation_status",nullable=false,length=24) public String validationStatus="valid";
    @Column(name="import_action",length=16) public String importAction;
    @Column(name="error_message",length=1000) public String errorMessage;
    @Column(name="expected_version") public Long expectedVersion;
    @Column(name="source_content_hash",length=64) public String sourceContentHash;
    @Column(name="source_content_hash_without_sku",length=64) public String sourceContentHashWithoutSku;
    @Column(name="target_product_id") public UUID targetProductId;
    @Column(name="before_sku",length=96) public String beforeSku;
    @Column(name="continuation_validation_status",length=24) public String continuationValidationStatus;
    @Column(name="continuation_import_action",length=16) public String continuationImportAction;
    @Column(name="continuation_error_message",length=1000) public String continuationErrorMessage;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="before_payload",columnDefinition="jsonb") public JsonNode beforePayload;
    @Column(name="before_catalog_state",length=24) public String beforeCatalogState;
    @Column(name="before_quote_ready") public Boolean beforeQuoteReady;
    @Column(name="before_source_hash",length=64) public String beforeSourceHash;
    @Column(name="before_version") public Long beforeVersion;
    @Column(name="before_product_asset_id") public UUID beforeProductAssetId;
    @Column(name="before_physical_asset_id") public UUID beforePhysicalAssetId;
    @Column(name="product_asset_id") public UUID productAssetId;
    @Column(name="physical_asset_id") public UUID physicalAssetId;
    @Column(name="applied_product_id") public UUID appliedProductId;
    @Column(name="applied_version") public Long appliedVersion;
    @Column(name="applied_at") public Instant appliedAt;
    @Column(name="rolled_back_at") public Instant rolledBackAt;
    @Column(name="created_at",nullable=false) public Instant createdAt;
    protected PurchaseImportRow() {}
}
