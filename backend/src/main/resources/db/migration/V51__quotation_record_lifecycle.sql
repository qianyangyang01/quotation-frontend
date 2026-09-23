-- Lifecycle is independent of commercial status; existing records remain active.
ALTER TABLE quotation_record ADD COLUMN lifecycle_state varchar(16) NOT NULL DEFAULT 'active';
ALTER TABLE quotation_record ADD CONSTRAINT quotation_record_lifecycle_check
    CHECK (lifecycle_state IN ('active', 'archived', 'trashed'));
CREATE INDEX quotation_record_lifecycle_created_idx ON quotation_record(lifecycle_state, created_at DESC, id DESC);
CREATE INDEX quotation_record_owner_lifecycle_idx ON quotation_record(owner_account, lifecycle_state, created_at DESC, id DESC);
