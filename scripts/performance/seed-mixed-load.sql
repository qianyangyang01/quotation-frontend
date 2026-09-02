BEGIN;

INSERT INTO purchase_product (
    id, sku, payload, version, created_at, updated_at, catalog_state, quote_ready, source_hash
) VALUES (
    '10000000-0000-4000-8000-000000000001',
    'LOAD-SKU-001',
    '{"sku":"LOAD-SKU-001","name":"混合压测标准商品","catalogState":"ready","quoteReady":true}'::jsonb,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    'ready',
    TRUE,
    repeat('1', 64)
) ON CONFLICT (sku) DO UPDATE SET
    payload = EXCLUDED.payload,
    catalog_state = 'ready',
    quote_ready = TRUE,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO logistics_provider (id, code, payload, version, created_at, updated_at) VALUES (
    '20000000-0000-4000-8000-000000000001',
    'LOAD-PROVIDER',
    '{"code":"LOAD-PROVIDER","name":"混压物流商","enabled":true}'::jsonb,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (code) DO NOTHING;

INSERT INTO logistics_channel (id, provider_id, code, rule_id, payload, version, created_at, updated_at) VALUES (
    '30000000-0000-4000-8000-000000000001',
    '20000000-0000-4000-8000-000000000001',
    'LOAD-CHANNEL',
    900001,
    '{"code":"LOAD-CHANNEL","name":"混压渠道","enabled":true}'::jsonb,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (code) DO NOTHING;

INSERT INTO logistics_version (
    id, channel_id, version_number, status, source_hash, payload, created_at, published_at
) VALUES (
    '40000000-0000-4000-8000-000000000001',
    '30000000-0000-4000-8000-000000000001',
    1,
    'published',
    repeat('2', 64),
    '{"status":"published","rows":[]}'::jsonb,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
) ON CONFLICT (channel_id, version_number) DO NOTHING;

UPDATE logistics_channel
SET current_version_id = '40000000-0000-4000-8000-000000000001', updated_at = CURRENT_TIMESTAMP
WHERE id = '30000000-0000-4000-8000-000000000001';

UPDATE finance_setting SET payload = '[{"code":"US","class":"standard"}]'::jsonb, updated_at = CURRENT_TIMESTAMP
WHERE setting_key = 'country-classification';
UPDATE finance_setting SET payload = '[{"channel":"LOAD-CHANNEL","enabled":true}]'::jsonb, updated_at = CURRENT_TIMESTAMP
WHERE setting_key = 'channel-policies';
UPDATE finance_setting SET payload = '[{"grade":"A","coefficient":1.0}]'::jsonb, updated_at = CURRENT_TIMESTAMP
WHERE setting_key = 'customer-grades';
UPDATE finance_setting SET payload = '{"usdCny":7.2,"updatedAt":"mixed-load"}'::jsonb, updated_at = CURRENT_TIMESTAMP
WHERE setting_key = 'exchange-rate';
UPDATE finance_setting SET payload = '{"countries":[{"code":"US","rate":0}],"providers":[{"code":"LOAD-PROVIDER","rate":0}],"updatedAt":"mixed-load"}'::jsonb, updated_at = CURRENT_TIMESTAMP
WHERE setting_key = 'tax-settings';

COMMIT;
