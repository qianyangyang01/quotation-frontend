ALTER TABLE asset_object
    ADD COLUMN storage_state VARCHAR(24) NOT NULL DEFAULT 'published',
    ADD COLUMN staging_job_id UUID,
    ADD COLUMN expires_at TIMESTAMPTZ;
CREATE INDEX idx_asset_staging_expiry ON asset_object(storage_state, expires_at);

UPDATE asset_object asset
SET storage_state = 'temporary', expires_at = CURRENT_TIMESTAMP + INTERVAL '7 days'
WHERE NOT EXISTS (SELECT 1 FROM purchase_product_image image WHERE image.asset_id = asset.id);

UPDATE import_job
SET status = 'failed', error_message = '历史预览缺少阻断校验结果，已禁止确认，请重新上传', updated_at = CURRENT_TIMESTAMP
WHERE job_type = 'purchase-xlsx' AND status = 'preview'
  AND NOT COALESCE((payload ->> 'canConfirm')::BOOLEAN, FALSE);
