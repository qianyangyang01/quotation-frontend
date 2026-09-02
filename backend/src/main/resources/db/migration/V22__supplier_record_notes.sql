CREATE TABLE supplier_record (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    industry_belt VARCHAR(160),
    contact_role VARCHAR(80),
    relationship_notes VARCHAR(160),
    invoice_type VARCHAR(40),
    tax_point NUMERIC(7,6) CHECK (tax_point IS NULL OR (tax_point >= 0 AND tax_point <= 1)),
    quality_grade VARCHAR(40),
    delivery_terms VARCHAR(80),
    capacity_order VARCHAR(120),
    stocking_strategy VARCHAR(160),
    alternative_inquiry VARCHAR(500),
    cost_sheet VARCHAR(500),
    hot_product_recommendation BOOLEAN,
    free_sample BOOLEAN,
    after_sales VARCHAR(160),
    cooperation_score INTEGER CHECK (cooperation_score IS NULL OR (cooperation_score >= 0 AND cooperation_score <= 100)),
    rating VARCHAR(20),
    monthly_purchase_amount NUMERIC(18,2) CHECK (monthly_purchase_amount IS NULL OR monthly_purchase_amount >= 0),
    notes VARCHAR(2000),
    suggestion VARCHAR(2000),
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_supplier_record_updated ON supplier_record(updated_at DESC);
CREATE INDEX idx_supplier_record_name_trgm ON supplier_record USING gin (lower(name) gin_trgm_ops);
