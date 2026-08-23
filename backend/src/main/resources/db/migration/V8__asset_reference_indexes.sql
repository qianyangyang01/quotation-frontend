-- PostgreSQL does not create indexes for foreign-key columns automatically.
-- These indexes keep orphan cleanup and asset-retention jobs bounded when the
-- procurement catalogue grows to hundreds of thousands of image relations.
CREATE INDEX idx_purchase_product_image_asset_id
    ON purchase_product_image(asset_id);

CREATE INDEX idx_purchase_import_row_product_asset_id
    ON purchase_import_row(product_asset_id)
    WHERE product_asset_id IS NOT NULL;

CREATE INDEX idx_purchase_import_row_physical_asset_id
    ON purchase_import_row(physical_asset_id)
    WHERE physical_asset_id IS NOT NULL;

CREATE INDEX idx_migration_manifest_entry_asset_id
    ON migration_manifest_entry(asset_id)
    WHERE asset_id IS NOT NULL;
