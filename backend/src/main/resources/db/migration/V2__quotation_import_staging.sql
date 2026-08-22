CREATE TABLE purchase_import_row (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES import_job(id) ON DELETE CASCADE,
    source_row INTEGER NOT NULL,
    sku VARCHAR(96) NOT NULL,
    payload JSONB NOT NULL,
    product_asset_id UUID REFERENCES asset_object(id),
    physical_asset_id UUID REFERENCES asset_object(id),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(job_id, source_row)
);
CREATE INDEX idx_purchase_import_job ON purchase_import_row(job_id, source_row);

CREATE TABLE migration_manifest_entry (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES import_job(id) ON DELETE CASCADE,
    sku VARCHAR(96) NOT NULL,
    image_type VARCHAR(24) NOT NULL,
    file_name VARCHAR(512) NOT NULL,
    expected_sha256 CHAR(64),
    status VARCHAR(24) NOT NULL,
    error_message VARCHAR(1000),
    asset_id UUID REFERENCES asset_object(id),
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(job_id, file_name)
);
CREATE INDEX idx_manifest_job_status ON migration_manifest_entry(job_id, status);
