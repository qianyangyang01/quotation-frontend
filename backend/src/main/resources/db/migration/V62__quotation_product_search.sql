-- Derived search data only: never rewrite saved prices, reviews or business timestamps.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE FUNCTION quotation_product_search_text(quote_no text, payload jsonb)
RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT lower(concat_ws(' ', quote_no, payload->'quotationBatch'->>'no',
        payload->>'primarySku', payload->>'productSummary', payload->>'salespersonName',
        (SELECT string_agg(concat_ws(' ', b->>'sku', b->>'name'), ' ')
         FROM jsonb_array_elements(
             CASE WHEN jsonb_typeof(payload->'bundleItems')='array'
                  THEN payload->'bundleItems' ELSE '[]'::jsonb END) b)))
$$;

ALTER TABLE quotation_record ADD COLUMN product_search_text text
    GENERATED ALWAYS AS (quotation_product_search_text(quote_no, payload)) STORED;
CREATE INDEX quotation_record_product_search_idx
    ON quotation_record USING gin (product_search_text gin_trgm_ops);
