-- No dataset activation or price publication is performed by this migration.
CREATE TABLE logistics_required_revision (
    dataset_id UUID NOT NULL REFERENCES logistics_dataset(id),
    revision BIGINT NOT NULL,
    payload JSONB NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(dataset_id,revision)
);
CREATE TABLE logistics_billing_acceptance (
    id UUID PRIMARY KEY,
    version_id UUID NOT NULL REFERENCES logistics_version(id),
    rows_fingerprint TEXT NOT NULL,
    engine_version TEXT NOT NULL,
    kind VARCHAR(24) NOT NULL CHECK(kind IN ('verified','legacy')),
    payload JSONB NOT NULL,
    reviewed_by VARCHAR(120) NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX logistics_acceptance_version ON logistics_billing_acceptance(version_id,reviewed_at DESC);
-- Grandfather only immutable versions already published in the original active library.
-- New imports (even into that library) must pass the new acceptance workflow.
INSERT INTO logistics_billing_acceptance
SELECT gen_random_uuid(),v.id,md5(coalesce(v.payload->'rows','[]'::jsonb)::text),'legacy','legacy',
       '{"note":"迁移前已发布旧库版本，仅保留兼容，不代表完成新库计费验收"}'::jsonb,'migration',now()
FROM logistics_version v JOIN logistics_channel c ON c.id=v.channel_id
WHERE c.dataset_id='00000000-0000-0000-0000-000000000001'
  AND v.status IN ('published','superseded') AND coalesce((v.payload->>'quoteReady')::boolean,true);

CREATE FUNCTION logistics_version_quote_ready(selected_version UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
SELECT EXISTS (
  SELECT 1 FROM logistics_version v JOIN logistics_channel c ON c.id=v.channel_id
  JOIN logistics_billing_acceptance a ON a.version_id=v.id
  WHERE v.id=selected_version AND v.status='published'
    AND a.rows_fingerprint=md5(coalesce(v.payload->'rows','[]'::jsonb)::text)
    AND ((a.kind='verified' AND a.engine_version='logistics-billing-v1')
      OR (a.kind='legacy' AND c.dataset_id='00000000-0000-0000-0000-000000000001'))
);
$$;
