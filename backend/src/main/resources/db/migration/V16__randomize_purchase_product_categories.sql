CREATE TABLE purchase_product_category_backup_v16 (
    product_id UUID PRIMARY KEY,
    sku VARCHAR(96) NOT NULL,
    had_category BOOLEAN NOT NULL,
    previous_category JSONB,
    previous_version BIGINT NOT NULL,
    backed_up_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    restored_at TIMESTAMPTZ
);

INSERT INTO purchase_product_category_backup_v16 (
    product_id,
    sku,
    had_category,
    previous_category,
    previous_version
)
SELECT
    id,
    sku,
    payload ? 'category',
    payload -> 'category',
    version
FROM purchase_product;

WITH ranked_products AS (
    SELECT
        id,
        row_number() OVER (
            ORDER BY md5(sku || ':milano-product-category-v1'), sku
        ) AS category_position
    FROM purchase_product
), assigned_categories AS (
    SELECT
        id,
        (ARRAY[
            '文胸', '袜子', '内裤', '服装', '化妆品', '保健品', '日用品',
            '庭院工具', '家用电器', '健身器材', '厨房用具', '家纺', '配饰',
            '鞋', '文具', '灯具', '数码', '辅料', '玩具', '书籍', '宠物用品',
            '医疗', '汽车用品', '清洁用品', '箱包', '护肤品', '其他'
        ]::TEXT[])[1 + ((category_position - 1) % 27)::INTEGER] AS category
    FROM ranked_products
)
UPDATE purchase_product AS product
SET payload = jsonb_set(product.payload, '{category}', to_jsonb(assigned.category), true),
    version = product.version + 1,
    updated_at = CURRENT_TIMESTAMP
FROM assigned_categories AS assigned
WHERE product.id = assigned.id;
