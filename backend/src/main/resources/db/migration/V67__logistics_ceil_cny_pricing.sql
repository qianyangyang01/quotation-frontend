-- New explicit tariff only. Historical source rows, approvals and quotations are unchanged.
ALTER FUNCTION logistics_price_row_quote_supported(JSONB) RENAME TO logistics_price_row_quote_supported_v66;
CREATE FUNCTION logistics_price_row_quote_supported(price JSONB) RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS $$
SELECT CASE WHEN price->>'pricingModel'='per-kg-1g-ceil-cny' THEN
  logistics_price_row_quote_supported_v66(price || '{"pricingModel":"per-kg-1g"}'::jsonb)
  AND coalesce(price->>'currency','CNY')='CNY'
  AND coalesce((price->>'billingStepKg')::numeric,0) IN (0,0.001)
  AND (NOT price ? 'billingStepBands' OR price->'billingStepBands'='null'::jsonb)
  ELSE logistics_price_row_quote_supported_v66(price) END;
$$;
CREATE OR REPLACE FUNCTION logistics_version_quote_ready(selected_version UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
SELECT EXISTS (
  SELECT 1 FROM logistics_version v JOIN logistics_channel c ON c.id=v.channel_id
  JOIN logistics_billing_acceptance a ON a.version_id=v.id
  WHERE v.id=selected_version AND v.status='published' AND a.rows_fingerprint=v.rows_fingerprint
    AND ((a.kind IN ('verified','validated-import') AND a.engine_version IN ('logistics-billing-v3','logistics-billing-v4','logistics-billing-v5','logistics-billing-v6','logistics-billing-v7','logistics-billing-v8','logistics-billing-v9','logistics-billing-v10'))
      OR (a.kind='legacy' AND c.dataset_id='00000000-0000-0000-0000-000000000001'))
    AND EXISTS (SELECT 1 FROM jsonb_array_elements(v.quote_rows) item WHERE logistics_price_row_quote_supported(item))
    AND (NOT EXISTS (SELECT 1 FROM jsonb_array_elements(v.quote_rows) item WHERE item->>'pricingModel'='per-piece-500g')
         OR a.engine_version IN ('logistics-billing-v6','logistics-billing-v7','logistics-billing-v8','logistics-billing-v9','logistics-billing-v10'))
    AND (NOT EXISTS (SELECT 1 FROM jsonb_array_elements(v.quote_rows) item WHERE item->>'pricingModel'='per-kg-1g')
         OR a.engine_version IN ('logistics-billing-v7','logistics-billing-v8','logistics-billing-v9','logistics-billing-v10'))
    AND (NOT EXISTS (SELECT 1 FROM jsonb_array_elements(v.quote_rows) item WHERE item->>'pricingModel'='per-kg-1g-ceil-cny')
         OR a.engine_version='logistics-billing-v10')
);
$$;
