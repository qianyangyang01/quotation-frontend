\set ON_ERROR_STOP on
DO $$ BEGIN IF current_database()<>'quotation_perf' THEN RAISE EXCEPTION 'Isolated database only'; END IF; END $$;
SELECT 'original-purchases',count(*),md5(coalesce(string_agg(md5(id::text||sku||payload::text||version::text),'' ORDER BY id),'')) FROM purchase_product WHERE sku NOT LIKE 'QA-%';
SELECT 'original-quotes',count(*),md5(coalesce(string_agg(md5(id::text||payload::text||version::text||lifecycle_state),'' ORDER BY id),'')) FROM quotation_record WHERE owner_account NOT LIKE 'LOAD%' AND owner_account NOT LIKE 'PERF%';
SELECT 'original-reviews',count(*),md5(coalesce(string_agg(md5(row_to_json(r)::text),'' ORDER BY r.id),'')) FROM quotation_review r JOIN quotation_record q ON q.id=r.id WHERE q.owner_account NOT LIKE 'LOAD%' AND q.owner_account NOT LIKE 'PERF%';
SELECT 'finance-values',count(*),md5(coalesce(string_agg(md5(setting_key||payload::text),'' ORDER BY setting_key),'')) FROM finance_setting;
SELECT 'unrelated-logistics',count(*),md5(coalesce(string_agg(md5(c.id::text||c.payload::text||coalesce(v.payload::text,'')),'' ORDER BY c.id),'')) FROM logistics_channel c JOIN logistics_provider p ON p.id=c.provider_id LEFT JOIN logistics_version v ON v.id=c.current_version_id WHERE p.payload->>'name' NOT LIKE '隔离导入%';
