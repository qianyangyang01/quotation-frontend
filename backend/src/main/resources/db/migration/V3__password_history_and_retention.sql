CREATE TABLE password_change_history (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    changed_by VARCHAR(24) NOT NULL,
    change_type VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_password_history_user ON password_change_history(user_id, created_at DESC);
CREATE INDEX idx_idempotency_created ON idempotency_record(created_at);
CREATE INDEX idx_import_job_requested ON import_job(requested_by, created_at DESC);
