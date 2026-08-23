ALTER TABLE business_migration_batch
    ADD COLUMN source_type VARCHAR(40) NOT NULL DEFAULT 'legacy-browser-report',
    ADD COLUMN diff JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN checkpoint JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN request_id VARCHAR(64),
    ADD COLUMN last_error VARCHAR(1000),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX idx_business_migration_status_updated
    ON business_migration_batch(status, updated_at DESC);
