BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM purchase_product_category_backup_v16
        WHERE restored_at IS NULL
    ) THEN
        RAISE EXCEPTION 'V16 purchase category backup has already been restored or is empty';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM purchase_product_category_backup_v16 AS backup
        LEFT JOIN purchase_product AS product ON product.id = backup.product_id
        WHERE backup.restored_at IS NULL
          AND (product.id IS NULL OR product.version <> backup.previous_version + 1)
    ) THEN
        RAISE EXCEPTION 'Purchase products changed after V16; refusing to overwrite newer data';
    END IF;
END $$;

UPDATE purchase_product AS product
SET payload = CASE
        WHEN backup.had_category
            THEN jsonb_set(product.payload, '{category}', backup.previous_category, true)
        ELSE product.payload - 'category'
    END,
    version = product.version + 1,
    updated_at = CURRENT_TIMESTAMP
FROM purchase_product_category_backup_v16 AS backup
WHERE product.id = backup.product_id
  AND backup.restored_at IS NULL;

UPDATE purchase_product_category_backup_v16
SET restored_at = CURRENT_TIMESTAMP
WHERE restored_at IS NULL;

COMMIT;
