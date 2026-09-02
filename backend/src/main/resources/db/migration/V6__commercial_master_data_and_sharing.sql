CREATE TABLE customer (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    contact_name VARCHAR(80),
    phone VARCHAR(40),
    email VARCHAR(160),
    country_code VARCHAR(8),
    grade VARCHAR(40),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    notes VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_customer_name ON customer(name);
CREATE INDEX idx_customer_enabled_updated ON customer(enabled, updated_at DESC);

CREATE TABLE supplier (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    contact_name VARCHAR(80),
    phone VARCHAR(40),
    platform VARCHAR(120),
    category VARCHAR(160),
    settlement_terms VARCHAR(160),
    lead_time_days INTEGER CHECK (lead_time_days IS NULL OR lead_time_days >= 0),
    rating NUMERIC(3,2) CHECK (rating IS NULL OR (rating >= 0 AND rating <= 5)),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_supplier_name ON supplier(name);
CREATE INDEX idx_supplier_enabled_updated ON supplier(enabled, updated_at DESC);

CREATE TABLE supplier_product (
    id UUID PRIMARY KEY,
    supplier_id UUID NOT NULL REFERENCES supplier(id),
    product_id UUID NOT NULL REFERENCES purchase_product(id),
    supplier_sku VARCHAR(96),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (supplier_id, product_id)
);

ALTER TABLE quotation_record
    ADD COLUMN customer_id UUID REFERENCES customer(id),
    ADD COLUMN voided_at TIMESTAMPTZ,
    ADD COLUMN voided_by VARCHAR(24),
    ADD COLUMN void_reason VARCHAR(500);

CREATE TABLE quotation_share (
    id UUID PRIMARY KEY,
    quotation_id UUID NOT NULL REFERENCES quotation_record(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_by VARCHAR(24) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_quotation_share_quote ON quotation_share(quotation_id, created_at DESC);

CREATE TABLE logistics_rule (
    id UUID PRIMARY KEY,
    version_id UUID NOT NULL REFERENCES logistics_version(id) ON DELETE CASCADE,
    rule_key VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (version_id, rule_key)
);
CREATE TABLE logistics_area (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL REFERENCES logistics_rule(id) ON DELETE CASCADE,
    area_key VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (rule_id, area_key)
);
CREATE TABLE logistics_condition (
    id UUID PRIMARY KEY,
    rule_id UUID NOT NULL REFERENCES logistics_rule(id) ON DELETE CASCADE,
    condition_key VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (rule_id, condition_key)
);

CREATE TABLE business_migration_batch (
    id UUID PRIMARY KEY,
    source_origin VARCHAR(255) NOT NULL,
    source_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL,
    requested_by VARCHAR(24) NOT NULL,
    counts JSONB NOT NULL,
    report JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

INSERT INTO finance_setting(setting_key, payload, version, updated_at) VALUES
 ('country-classification', '{"countries":[]}'::jsonb, 0, CURRENT_TIMESTAMP),
 ('channel-policies', '{"policies":[]}'::jsonb, 0, CURRENT_TIMESTAMP),
 ('customer-grades', '{"grades":[]}'::jsonb, 0, CURRENT_TIMESTAMP),
 ('exchange-rate', '{"usdToCny":null,"effectiveAt":null}'::jsonb, 0, CURRENT_TIMESTAMP),
 ('tax-settings', '{"rules":[]}'::jsonb, 0, CURRENT_TIMESTAMP)
ON CONFLICT (setting_key) DO NOTHING;
