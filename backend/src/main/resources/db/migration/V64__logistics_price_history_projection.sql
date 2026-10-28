-- Derived display data only. Published source versions and saved quotations remain unchanged.
CREATE TABLE logistics_price_history_projection (
    version_id UUID PRIMARY KEY REFERENCES logistics_version(id) ON DELETE CASCADE,
    rows_fingerprint TEXT NOT NULL,
    engine_version TEXT NOT NULL,
    prices JSONB NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
