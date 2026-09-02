ALTER TABLE import_job
    ADD COLUMN phase VARCHAR(32),
    ADD COLUMN source_object_key VARCHAR(512),
    ADD COLUMN total_rows INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN processed_rows INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN valid_rows INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN error_rows INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN added_rows INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN updated_rows INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN conflict_rows INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN progress_percent INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN heartbeat_at TIMESTAMPTZ,
    ADD COLUMN confirmed_at TIMESTAMPTZ,
    ADD COLUMN rolled_back_at TIMESTAMPTZ;

ALTER TABLE purchase_import_row
    ADD COLUMN validation_status VARCHAR(24) NOT NULL DEFAULT 'valid',
    ADD COLUMN import_action VARCHAR(16),
    ADD COLUMN error_message VARCHAR(1000),
    ADD COLUMN expected_version BIGINT,
    ADD COLUMN before_payload JSONB,
    ADD COLUMN before_catalog_state VARCHAR(24),
    ADD COLUMN before_quote_ready BOOLEAN,
    ADD COLUMN before_source_hash VARCHAR(64),
    ADD COLUMN before_version BIGINT,
    ADD COLUMN before_product_asset_id UUID REFERENCES asset_object(id),
    ADD COLUMN before_physical_asset_id UUID REFERENCES asset_object(id),
    ADD COLUMN applied_product_id UUID,
    ADD COLUMN applied_version BIGINT,
    ADD COLUMN applied_at TIMESTAMPTZ,
    ADD COLUMN rolled_back_at TIMESTAMPTZ;

ALTER TABLE import_part
    ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'uploaded',
    ADD COLUMN error_message VARCHAR(1000),
    ADD COLUMN processed_at TIMESTAMPTZ,
    ADD COLUMN processed_bytes BIGINT NOT NULL DEFAULT 0;

ALTER TABLE migration_manifest_entry
    ADD COLUMN import_part_id UUID REFERENCES import_part(id) ON DELETE CASCADE,
    ADD COLUMN asset_owned BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_import_job_purchase_queue
    ON import_job(job_type, status, created_at)
    WHERE job_type = 'purchase-xlsx-async';
CREATE UNIQUE INDEX uq_import_job_single_active_purchase
    ON import_job ((1))
    WHERE job_type = 'purchase-xlsx-async'
      AND status IN ('parsing', 'importing', 'rolling-back');
CREATE INDEX idx_purchase_import_job_status
    ON purchase_import_row(job_id, validation_status, source_row);
CREATE INDEX idx_purchase_import_job_action
    ON purchase_import_row(job_id, import_action, source_row);
CREATE INDEX idx_purchase_import_job_applied
    ON purchase_import_row(job_id, applied_at)
    WHERE applied_at IS NOT NULL;
CREATE INDEX idx_migration_manifest_import_part
    ON migration_manifest_entry(import_part_id);
CREATE INDEX idx_purchase_product_sku_origin
    ON purchase_product ((payload ->> 'skuOrigin'));
