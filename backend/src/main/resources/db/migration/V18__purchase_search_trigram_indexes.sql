CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_purchase_product_sku_trgm
    ON purchase_product USING gin (lower(sku) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_purchase_product_payload_trgm
    ON purchase_product USING gin ((lower(payload::text)) gin_trgm_ops);
