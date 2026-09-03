-- Reviewable imports: validated parser output may become quote-ready at publish time.
ALTER TABLE logistics_billing_acceptance DROP CONSTRAINT logistics_billing_acceptance_kind_check;
ALTER TABLE logistics_billing_acceptance ADD CONSTRAINT logistics_billing_acceptance_kind_check
    CHECK(kind IN ('verified','legacy','validated-import'));

CREATE UNIQUE INDEX logistics_validated_import_once
    ON logistics_billing_acceptance(version_id,rows_fingerprint,engine_version,kind)
    WHERE kind='validated-import';

CREATE OR REPLACE FUNCTION logistics_version_quote_ready(selected_version UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
SELECT EXISTS (
  SELECT 1 FROM logistics_version v JOIN logistics_channel c ON c.id=v.channel_id
  JOIN logistics_billing_acceptance a ON a.version_id=v.id
  WHERE v.id=selected_version AND v.status='published'
    AND a.rows_fingerprint=v.rows_fingerprint
    AND ((a.kind IN ('verified','validated-import') AND a.engine_version='logistics-billing-v3')
      OR (a.kind='legacy' AND c.dataset_id='00000000-0000-0000-0000-000000000001'))
);
$$;

-- Only newly uploaded files receive lifecycle rows. Historical raw files are deliberately not backfilled.
CREATE TABLE logistics_import_file (
    batch_id UUID NOT NULL REFERENCES logistics_import_batch(id) ON DELETE CASCADE,
    file_index INTEGER NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    object_key TEXT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK(size_bytes>=0),
    status VARCHAR(24) NOT NULL CHECK(status IN ('stored','parsed','failed','delete-pending','deleted')),
    parser_version VARCHAR(80),
    retention_until TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    delete_error VARCHAR(500),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(batch_id,file_index)
);
CREATE INDEX logistics_import_file_cleanup ON logistics_import_file(status,retention_until,updated_at);
