DROP INDEX IF EXISTS idx_purchase_import_job_sheet_row;

ALTER TABLE purchase_import_row
    DROP CONSTRAINT IF EXISTS purchase_import_row_job_id_source_row_key;

ALTER TABLE purchase_import_row
    ADD CONSTRAINT purchase_import_row_job_id_source_sheet_source_row_key
        UNIQUE (job_id, source_sheet, source_row);
