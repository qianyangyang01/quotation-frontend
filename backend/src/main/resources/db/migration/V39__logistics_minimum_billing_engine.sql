-- Accept newly checked v5 minimum-weight results without rewriting formal source versions.
CREATE OR REPLACE FUNCTION logistics_price_row_quote_supported(price JSONB) RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS $$
SELECT coalesce(
  coalesce(nullif(price->>'pricingModel',''),'per-kg')='per-kg'
  AND CASE WHEN jsonb_typeof(price->'pricePerKg')='number' THEN (price->>'pricePerKg')::numeric>0 ELSE false END
  AND NOT EXISTS (SELECT 1 FROM unnest(ARRAY['firstWeightKg','firstWeightPrice','nextWeightKg','nextWeightPrice','intervalPrice','surcharge']) field
                  WHERE coalesce(price->>field,'0') !~ '^0+(\.0+)?$')
  AND coalesce(price->>'pendingReason','') !~ '(最低|最小)计费重量'
  AND NOT EXISTS (SELECT 1 FROM unnest(ARRAY['minChargeWeightKg','startWeightKg']) field WHERE
    CASE WHEN price->field IS NULL OR jsonb_typeof(price->field)='null' THEN false
         WHEN jsonb_typeof(price->field)='number' THEN (price->>field)::numeric<0
         ELSE true END)
  AND NOT EXISTS (SELECT 1 FROM unnest(ARRAY['minChargeWeightKg','startWeightKg']) field WHERE
    CASE WHEN jsonb_typeof(price->field)='number' AND jsonb_typeof(price->'weightToKg')='number' THEN
      (price->>field)::numeric>(price->>'weightToKg')::numeric OR
      ((price->>field)::numeric>0 AND (price->>field)::numeric=(price->>'weightToKg')::numeric AND price->>'weightToInclusive'='false')
    ELSE false END),false);
$$;

CREATE OR REPLACE FUNCTION logistics_version_quote_ready(selected_version UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
SELECT EXISTS (
  SELECT 1 FROM logistics_version v JOIN logistics_channel c ON c.id=v.channel_id
  JOIN logistics_billing_acceptance a ON a.version_id=v.id
  WHERE v.id=selected_version AND v.status='published'
    AND a.rows_fingerprint=v.rows_fingerprint
    AND ((a.kind IN ('verified','validated-import') AND a.engine_version IN ('logistics-billing-v3','logistics-billing-v4','logistics-billing-v5'))
      OR (a.kind='legacy' AND c.dataset_id='00000000-0000-0000-0000-000000000001'))
    AND EXISTS (SELECT 1 FROM jsonb_array_elements(v.quote_rows) item WHERE logistics_price_row_quote_supported(item))
);
$$;
