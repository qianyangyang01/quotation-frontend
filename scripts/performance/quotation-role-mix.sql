\set ON_ERROR_STOP on
DO $$ BEGIN IF current_database() <> 'quotation_perf' THEN RAISE EXCEPTION 'Isolated database required'; END IF; END $$;
UPDATE app_user SET role_key=CASE WHEN account BETWEEN 'PERF41' AND 'PERF44' THEN 'purchase' WHEN account BETWEEN 'PERF45' AND 'PERF47' THEN 'finance' ELSE 'logistics' END, version=version+1, updated_at=now()
WHERE account BETWEEN 'PERF41' AND 'PERF50';
SELECT role_key,count(*) FROM app_user WHERE account ~ '^PERF[0-9]{2}$' GROUP BY role_key ORDER BY role_key;
