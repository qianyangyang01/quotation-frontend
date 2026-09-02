ALTER TABLE purchase_import_row
    ADD COLUMN source_sheet VARCHAR(128) NOT NULL DEFAULT '采购产品导入';

CREATE INDEX idx_purchase_import_job_sheet_row
    ON purchase_import_row(job_id, source_sheet, source_row);
