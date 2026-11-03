-- A new, independent pricing setting only. No quotation or review rows are updated.
INSERT INTO finance_setting(setting_key,payload,version,updated_at)
VALUES ('womens-pricing', '{"schemaVersion":1,"basis":"total-cost-usd","enabled":true,"tiers":[{"fromUsd":0,"toUsd":20,"coefficient":1.27},{"fromUsd":20,"toUsd":30,"coefficient":1.25},{"fromUsd":30,"toUsd":50,"coefficient":1.23},{"fromUsd":50,"toUsd":null,"coefficient":1.22}]}'::jsonb, 0, now())
ON CONFLICT (setting_key) DO NOTHING;
