ALTER TABLE import_job ADD COLUMN archived_at TIMESTAMPTZ;
CREATE INDEX idx_purchase_import_job_visible ON import_job (created_at DESC, id DESC)
    WHERE job_type = 'purchase-xlsx-async' AND archived_at IS NULL;
CREATE INDEX idx_purchase_import_job_archived ON import_job (created_at DESC, id DESC)
    WHERE job_type = 'purchase-xlsx-async' AND archived_at IS NOT NULL;
