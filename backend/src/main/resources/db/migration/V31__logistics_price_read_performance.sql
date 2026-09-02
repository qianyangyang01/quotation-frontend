-- Persist the immutable fingerprint that billing acceptance already compares.
-- This avoids re-serializing and hashing every version's full price JSON on each read.
ALTER TABLE logistics_version
    ADD COLUMN rows_fingerprint TEXT
    GENERATED ALWAYS AS (md5(coalesce(payload->'rows','[]'::jsonb)::text)) STORED;

CREATE INDEX logistics_acceptance_ready_lookup
    ON logistics_billing_acceptance(version_id,rows_fingerprint,kind,engine_version);

CREATE OR REPLACE FUNCTION logistics_version_quote_ready(selected_version UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
SELECT EXISTS (
  SELECT 1 FROM logistics_version v JOIN logistics_channel c ON c.id=v.channel_id
  JOIN logistics_billing_acceptance a ON a.version_id=v.id
  WHERE v.id=selected_version AND v.status='published'
    AND a.rows_fingerprint=v.rows_fingerprint
    AND ((a.kind='verified' AND a.engine_version='logistics-billing-v3')
      OR (a.kind='legacy' AND c.dataset_id='00000000-0000-0000-0000-000000000001'))
);
$$;
