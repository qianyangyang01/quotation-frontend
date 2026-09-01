-- Equivalent physical source rows with the same route and price share one billing
-- acceptance tier. Only evidence reviewed by the matching v3 engine is valid.
CREATE OR REPLACE FUNCTION logistics_version_quote_ready(selected_version UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
SELECT EXISTS (
  SELECT 1 FROM logistics_version v JOIN logistics_channel c ON c.id=v.channel_id
  JOIN logistics_billing_acceptance a ON a.version_id=v.id
  WHERE v.id=selected_version AND v.status='published'
    AND a.rows_fingerprint=md5(coalesce(v.payload->'rows','[]'::jsonb)::text)
    AND ((a.kind='verified' AND a.engine_version='logistics-billing-v3')
      OR (a.kind='legacy' AND c.dataset_id='00000000-0000-0000-0000-000000000001'))
);
$$;
