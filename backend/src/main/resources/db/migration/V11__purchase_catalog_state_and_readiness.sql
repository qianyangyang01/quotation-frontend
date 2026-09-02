ALTER TABLE purchase_product
    ADD COLUMN catalog_state VARCHAR(24) NOT NULL DEFAULT 'ready',
    ADD COLUMN quote_ready BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN source_hash VARCHAR(64);

UPDATE purchase_product
SET catalog_state = CASE
        WHEN sku ~* '^(TESTP|TEST|DEMO|MOCK)' OR sku LIKE 'AUTO-%' THEN 'pending_template'
        WHEN payload ->> 'catalogState' IN ('pending_template', 'ready', 'disabled') THEN payload ->> 'catalogState'
        ELSE 'ready'
    END;

UPDATE purchase_product
SET quote_ready = catalog_state = 'ready'
    AND lower(COALESCE(payload ->> 'quoteReady', 'false')) = 'true';

ALTER TABLE purchase_product
    ADD CONSTRAINT chk_purchase_product_catalog_state
        CHECK (catalog_state IN ('pending_template', 'ready', 'disabled'));

CREATE INDEX idx_purchase_product_catalog_state_updated
    ON purchase_product(catalog_state, updated_at DESC);

CREATE INDEX idx_purchase_product_quote_ready_updated
    ON purchase_product(quote_ready, updated_at DESC);
