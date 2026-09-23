CREATE INDEX idx_audit_resource_history ON audit_log(resource_type, resource_id, created_at DESC, id DESC);
