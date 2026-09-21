-- Opt in to the confirmed half-kilogram parcel tariff. Preserve old source data and approvals.
ALTER FUNCTION logistics_price_row_quote_supported(JSONB) RENAME TO logistics_price_row_quote_supported_v39;

CREATE FUNCTION logistics_price_row_quote_supported(price JSONB) RETURNS BOOLEAN LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE lo numeric; hi numeric; floor_weight numeric;
BEGIN
  IF coalesce(price->>'pricingModel','') <> 'per-piece-500g' THEN
    RETURN logistics_price_row_quote_supported_v39(price);
  END IF;
  IF coalesce(price->>'currency','CNY') <> 'CNY'
     OR coalesce(price->>'pendingReason','') ~ '(最低|最小)计费重量'
     OR coalesce(price->>'weightFromInclusive','false') <> 'false'
     OR coalesce(price->>'weightToInclusive','true') <> 'true'
     OR EXISTS (SELECT 1 FROM unnest(ARRAY['weightFromKg','weightToKg','intervalPrice']) f
                WHERE coalesce(jsonb_typeof(price->f),'') <> 'number')
     OR EXISTS (SELECT 1 FROM unnest(ARRAY['pricePerKg','firstWeightKg','firstWeightPrice','nextWeightKg','nextWeightPrice','surcharge']) f
                WHERE coalesce(price->>f,'0') !~ '^0+(\.0+)?$')
     OR EXISTS (SELECT 1 FROM unnest(ARRAY['minChargeWeightKg','startWeightKg']) f
                WHERE CASE WHEN price->f IS NULL OR jsonb_typeof(price->f)='null' THEN false
                           WHEN jsonb_typeof(price->f)='number' THEN (price->>f)::numeric<0 ELSE true END)
  THEN RETURN false; END IF;
  lo := (price->>'weightFromKg')::numeric; hi := (price->>'weightToKg')::numeric;
  floor_weight := greatest(coalesce((price->>'minChargeWeightKg')::numeric,0),coalesce((price->>'startWeightKg')::numeric,0));
  RETURN lo>=0 AND hi-lo=0.5 AND mod(lo,0.5)=0 AND floor_weight=0.5 AND (price->>'intervalPrice')::numeric>0;
END;
$$;

-- Rebind the read projection to the new function; no stored source rows are rewritten.
CREATE OR REPLACE FUNCTION logistics_quote_read_rows(source JSONB) RETURNS JSONB LANGUAGE sql IMMUTABLE AS $$
SELECT coalesce(jsonb_agg(
  (SELECT coalesce(jsonb_object_agg(field.key,field.value),'{}'::jsonb)
   FROM jsonb_each(item) field WHERE field.key = ANY(ARRAY['areaName','countryCode','etaMinDays','etaMaxDays','etaStatus','prohibitedMarks','allowedMarks','maxPerimeterCm','maxSideCm','volumeDivisor','weightFromKg','weightToKg','startWeightKg','pricePerKg','minChargeWeightKg','firstWeightKg','firstWeightPrice','nextWeightKg','nextWeightPrice','intervalPrice','registrationFee','pricingModel','surcharge','fuelSurchargeRate','prohibitGeneralCargo','volumetric','phoneRequired','zoneName','zoneExclude','weightFromInclusive','weightToInclusive','pendingReason','currency']))
  ORDER BY ordinal),'[]'::jsonb)
FROM jsonb_array_elements(CASE WHEN jsonb_typeof(source->'rows')='array' THEN source->'rows' ELSE '[]'::jsonb END)
  WITH ORDINALITY AS prices(item,ordinal)
WHERE logistics_price_row_quote_supported(item);
$$;

CREATE OR REPLACE FUNCTION logistics_version_quote_ready(selected_version UUID) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
SELECT EXISTS (
  SELECT 1 FROM logistics_version v JOIN logistics_channel c ON c.id=v.channel_id
  JOIN logistics_billing_acceptance a ON a.version_id=v.id
  WHERE v.id=selected_version AND v.status='published'
    AND a.rows_fingerprint=v.rows_fingerprint
    AND ((a.kind IN ('verified','validated-import') AND a.engine_version IN ('logistics-billing-v3','logistics-billing-v4','logistics-billing-v5','logistics-billing-v6'))
      OR (a.kind='legacy' AND c.dataset_id='00000000-0000-0000-0000-000000000001'))
    AND EXISTS (SELECT 1 FROM jsonb_array_elements(v.quote_rows) item WHERE logistics_price_row_quote_supported(item))
    AND (NOT EXISTS (SELECT 1 FROM jsonb_array_elements(v.quote_rows) item WHERE item->>'pricingModel'='per-piece-500g')
         OR a.engine_version='logistics-billing-v6')
);
$$;
