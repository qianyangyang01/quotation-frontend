WITH readiness AS (
    SELECT id,
           catalog_state = 'ready'
               AND sku !~* '^(TESTP|TEST|DEMO|MOCK)'
               AND sku NOT LIKE 'AUTO-%'
               AND CASE WHEN jsonb_typeof(payload -> 'weightG') = 'number'
                   THEN (payload ->> 'weightG')::numeric > 0 ELSE false END
               AND CASE WHEN jsonb_typeof(payload -> 'minOrderQty') = 'number'
                   THEN (payload ->> 'minOrderQty')::numeric > 0 ELSE false END
               AND CASE WHEN jsonb_typeof(payload -> 'purchasePriceCny') = 'number'
                   THEN (payload ->> 'purchasePriceCny')::numeric >= 0 ELSE false END AS ready
    FROM purchase_product
)
UPDATE purchase_product product
SET quote_ready = readiness.ready,
    payload = jsonb_set(
        jsonb_set(product.payload, '{quoteReady}', to_jsonb(readiness.ready), true),
        '{status}',
        to_jsonb(CASE
            WHEN product.catalog_state = 'pending_template' THEN '模板待补全（不可报价）'
            WHEN product.catalog_state = 'disabled' THEN '已停用'
            WHEN readiness.ready THEN '资料完整'
            ELSE '待补充资料'
        END::text),
        true
    )
FROM readiness
WHERE product.id = readiness.id;
