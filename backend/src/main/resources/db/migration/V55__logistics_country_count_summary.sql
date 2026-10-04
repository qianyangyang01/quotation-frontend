-- Preserve the existing country-count semantics, but compute once when a
-- version payload is written instead of expanding every historical payload
-- on each workspace read. No quotation or review data is changed.
CREATE FUNCTION logistics_version_country_count(document jsonb)
RETURNS integer
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT count(DISTINCT coalesce(nullif(price->>'countryCode',''),price->>'areaName'))::integer
    FROM jsonb_array_elements(
        CASE WHEN jsonb_typeof(document->'rows')='array' THEN document->'rows' ELSE '[]'::jsonb END
    ) price
$$;

ALTER TABLE logistics_version
    ADD COLUMN country_count integer
    GENERATED ALWAYS AS (logistics_version_country_count(payload)) STORED;
