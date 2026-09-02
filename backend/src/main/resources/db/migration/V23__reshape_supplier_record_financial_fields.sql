ALTER TABLE supplier_record RENAME COLUMN contact_role TO boss_name;
ALTER TABLE supplier_record RENAME COLUMN relationship_notes TO contact_details;
ALTER TABLE supplier_record RENAME COLUMN cost_sheet TO corporate_account;

ALTER TABLE supplier_record ADD COLUMN corporate_bank VARCHAR(160);
ALTER TABLE supplier_record ADD COLUMN business_license_asset_id UUID;
ALTER TABLE supplier_record
    ADD CONSTRAINT fk_supplier_record_business_license_asset
    FOREIGN KEY (business_license_asset_id) REFERENCES asset_object(id);

CREATE INDEX idx_supplier_record_business_license_asset
    ON supplier_record(business_license_asset_id)
    WHERE business_license_asset_id IS NOT NULL;
