-- Preserve explicit rounding and its source identity in the quotation read path.
CREATE OR REPLACE FUNCTION logistics_quote_read_rows(source JSONB) RETURNS JSONB LANGUAGE sql IMMUTABLE AS $$
SELECT coalesce(jsonb_agg(
  (SELECT coalesce(jsonb_object_agg(field.key,field.value),'{}'::jsonb)
   FROM jsonb_each(item) field WHERE field.key = ANY(ARRAY['areaName','countryCode','etaMinDays','etaMaxDays','etaStatus','prohibitedMarks','allowedMarks','maxPerimeterCm','maxSideCm','volumeDivisor','weightFromKg','weightToKg','startWeightKg','pricePerKg','minChargeWeightKg','firstWeightKg','firstWeightPrice','nextWeightKg','nextWeightPrice','intervalPrice','registrationFee','pricingModel','surcharge','fuelSurchargeRate','prohibitGeneralCargo','volumetric','phoneRequired','zoneName','zoneExclude','weightFromInclusive','weightToInclusive','pendingReason','currency','billingStepKg','sourceSheet']))
  ORDER BY ordinal),'[]'::jsonb)
FROM jsonb_array_elements(CASE WHEN jsonb_typeof(source->'rows')='array' THEN source->'rows' ELSE '[]'::jsonb END)
  WITH ORDINALITY AS prices(item,ordinal)
WHERE logistics_price_row_quote_supported(item);
$$;

CREATE OR REPLACE FUNCTION logistics_version_quote_ready(selected_version UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
SELECT EXISTS (
  SELECT 1 FROM logistics_version v JOIN logistics_channel c ON c.id=v.channel_id
  JOIN logistics_billing_acceptance a ON a.version_id=v.id
  WHERE v.id=selected_version AND v.status='published' AND a.rows_fingerprint=v.rows_fingerprint
    AND ((a.kind IN ('verified','validated-import') AND a.engine_version IN ('logistics-billing-v3','logistics-billing-v4','logistics-billing-v5','logistics-billing-v6','logistics-billing-v7','logistics-billing-v8'))
      OR (a.kind='legacy' AND c.dataset_id='00000000-0000-0000-0000-000000000001'))
    AND EXISTS (SELECT 1 FROM jsonb_array_elements(v.quote_rows) item WHERE logistics_price_row_quote_supported(item))
    AND (NOT EXISTS (SELECT 1 FROM jsonb_array_elements(v.quote_rows) item WHERE item->>'pricingModel'='per-piece-500g')
         OR a.engine_version IN ('logistics-billing-v6','logistics-billing-v7','logistics-billing-v8'))
    AND (NOT EXISTS (SELECT 1 FROM jsonb_array_elements(v.quote_rows) item WHERE item->>'pricingModel'='per-kg-1g')
         OR a.engine_version IN ('logistics-billing-v7','logistics-billing-v8'))
);
$$;

-- Recompute only the generated read projection, preserving immutable source payloads,
-- price fingerprints, approvals, and historical quotations.
ALTER TABLE logistics_version DROP COLUMN quote_rows;
ALTER TABLE logistics_version ADD COLUMN quote_rows JSONB
  GENERATED ALWAYS AS (logistics_quote_read_rows(payload)) STORED;
