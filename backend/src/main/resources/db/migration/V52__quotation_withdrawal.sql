ALTER TABLE quotation_record DROP CONSTRAINT quotation_record_lifecycle_check;
ALTER TABLE quotation_record ADD CONSTRAINT quotation_record_lifecycle_check
    CHECK (lifecycle_state IN ('active', 'archived', 'trashed', 'withdrawn'));
ALTER TABLE quotation_draft ADD COLUMN source_quote_id uuid REFERENCES quotation_record(id);
ALTER TABLE quotation_draft ADD COLUMN source_quote_version bigint;
ALTER TABLE quotation_draft ADD CONSTRAINT quotation_draft_source_check
    CHECK ((source_quote_id IS NULL) = (source_quote_version IS NULL));
CREATE UNIQUE INDEX quotation_draft_source_idx ON quotation_draft(source_quote_id);
