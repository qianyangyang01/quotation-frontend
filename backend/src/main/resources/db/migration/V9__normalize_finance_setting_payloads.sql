-- V6 initially used named bootstrap containers while the established frontend
-- contract stores the list settings as JSON arrays. Normalize only those
-- legacy containers so user-saved canonical values remain untouched.
UPDATE finance_setting
SET payload = payload -> 'countries', updated_at = CURRENT_TIMESTAMP
WHERE setting_key = 'country-classification'
  AND jsonb_typeof(payload) = 'object'
  AND jsonb_typeof(payload -> 'countries') = 'array';

UPDATE finance_setting
SET payload = payload -> 'policies', updated_at = CURRENT_TIMESTAMP
WHERE setting_key = 'channel-policies'
  AND jsonb_typeof(payload) = 'object'
  AND jsonb_typeof(payload -> 'policies') = 'array';

UPDATE finance_setting
SET payload = payload -> 'grades', updated_at = CURRENT_TIMESTAMP
WHERE setting_key = 'customer-grades'
  AND jsonb_typeof(payload) = 'object'
  AND jsonb_typeof(payload -> 'grades') = 'array';

UPDATE finance_setting
SET payload = jsonb_build_object(
        'usdCny', payload -> 'usdToCny',
        'updatedAt', COALESCE(payload -> 'effectiveAt', to_jsonb('财务维护'::text))
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE setting_key = 'exchange-rate'
  AND jsonb_typeof(payload) = 'object'
  AND payload ? 'usdToCny'
  AND NOT payload ? 'usdCny';

UPDATE finance_setting
SET payload = jsonb_build_object('countries', '[]'::jsonb, 'providers', '[]'::jsonb, 'updatedAt', '尚未保存'),
    updated_at = CURRENT_TIMESTAMP
WHERE setting_key = 'tax-settings'
  AND jsonb_typeof(payload) = 'object'
  AND payload ? 'rules'
  AND NOT payload ? 'countries';
