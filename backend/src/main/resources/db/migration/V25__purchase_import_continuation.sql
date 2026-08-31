-- Continuation is derived from committed import rows, never from a maximum row
-- number alone. Existing pending jobs deliberately remain in their original mode.
ALTER TABLE purchase_import_row
    ADD COLUMN source_content_hash VARCHAR(64),
    ADD COLUMN source_content_hash_without_sku VARCHAR(64),
    ADD COLUMN target_product_id UUID,
    ADD COLUMN before_sku VARCHAR(96),
    ADD COLUMN continuation_validation_status VARCHAR(24),
    ADD COLUMN continuation_import_action VARCHAR(16),
    ADD COLUMN continuation_error_message VARCHAR(1000);

CREATE INDEX idx_purchase_import_source_history
    ON import_job(source_name, id)
    WHERE job_type = 'purchase-xlsx-async';
CREATE INDEX idx_purchase_import_active_source_rows
    ON purchase_import_row(job_id, source_sheet, source_row)
    WHERE applied_at IS NOT NULL AND rolled_back_at IS NULL;

CREATE TABLE purchase_import_source_revision (
    source_name VARCHAR(255) PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0
);

-- A statement-level trigger advances once per affected source, rather than once
-- per product. This also covers legacy asynchronous jobs and rollback batches.
CREATE FUNCTION advance_purchase_import_source_revision()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE changed_source TEXT;
BEGIN
    FOR changed_source IN
        SELECT DISTINCT j.source_name
          FROM current_rows n JOIN previous_rows o ON o.id = n.id
          JOIN import_job j ON j.id = n.job_id
         WHERE j.job_type = 'purchase-xlsx-async'
           AND (n.applied_at IS DISTINCT FROM o.applied_at
             OR n.rolled_back_at IS DISTINCT FROM o.rolled_back_at)
    LOOP
        INSERT INTO purchase_import_source_revision(source_name, revision) VALUES (changed_source, 1)
        ON CONFLICT (source_name) DO UPDATE SET revision = purchase_import_source_revision.revision + 1;
    END LOOP;
    RETURN NULL;
END;
$$;
CREATE TRIGGER purchase_import_source_revision_after_update
    AFTER UPDATE ON purchase_import_row
    REFERENCING OLD TABLE AS previous_rows NEW TABLE AS current_rows
    FOR EACH STATEMENT EXECUTE FUNCTION advance_purchase_import_source_revision();

-- Older imports do not have a raw-cell hash. Compare only the original mapped
-- purchase fields: generated AUTO ids, names, image URLs and derived readiness
-- must not make an unchanged source row look different on its next upload.
CREATE FUNCTION purchase_import_source_fingerprint(data JSONB, ignore_sku BOOLEAN DEFAULT FALSE)
RETURNS TEXT LANGUAGE SQL IMMUTABLE PARALLEL SAFE AS $$
    SELECT encode(sha256(convert_to(jsonb_object_agg(field, value)::text, 'UTF8')), 'hex')
      FROM (
        SELECT field, CASE
          WHEN field = 'sku' THEN to_jsonb(CASE
            WHEN ignore_sku OR data ->> 'skuOrigin' = 'system' THEN ''
            ELSE COALESCE(data ->> 'sku', '') END)
          WHEN field = ANY(ARRAY['weightG','lengthCm','widthCm','heightCm','minOrderQty',
            'purchasePriceCny','tier2MinQty','tier2PriceCny','tier3MinQty','tier3PriceCny',
            'singleFreightCny','freight10Cny','freight100Cny','taxIncludedPriceCny','taxPoint'])
            THEN CASE WHEN jsonb_typeof(data -> field) = 'number'
              THEN to_jsonb(trim_scale((data ->> field)::numeric)) ELSE 'null'::jsonb END
          ELSE to_jsonb(COALESCE(data ->> field, ''))
        END AS value
        FROM unnest(ARRAY['sku','category','quotationOwner','quotationDate','size','color',
          'material','weightG','lengthCm','widthCm','heightCm','minOrderQty',
          'purchasePriceCny','tier2MinQty','tier2PriceCny','tier3MinQty','tier3PriceCny',
          'singleFreightCny','freight10Cny','freight100Cny','freeShipping',
          'taxIncludedPriceCny','taxPoint','invoiceType','stockStatus','notes','factoryInfo',
          'auditNotes','sourceLink1','sourceLink2','sourceLink3','similarSource']) field
      ) normalized;
$$;
