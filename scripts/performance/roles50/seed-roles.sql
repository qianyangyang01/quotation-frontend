\set ON_ERROR_STOP on

-- Run after scripts/performance/seed.sql, only in the isolated performance database.
DO $$ BEGIN
  IF current_database() <> 'quotation_perf' THEN
    RAISE EXCEPTION 'Refusing to seed non-performance database';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM app_user WHERE account='PERFADMIN') THEN
    RAISE EXCEPTION 'Run the base performance fixture first';
  END IF;
END $$;

WITH users AS (
  SELECT 'PERF'||lpad(n::text,2,'0') AS account,
    CASE WHEN n<=20 THEN 'employee' WHEN n<=48 THEN 'purchase' ELSE 'logistics' END AS role_key
  FROM generate_series(1,50) n
)
INSERT INTO app_user(id,account,display_name,password_hash,role_key,status,must_change_password,
  password_updated_at,version,created_at,updated_at)
SELECT md5('perf-user-'||u.account)::uuid,u.account,'隔离性能账号'||u.account,a.password_hash,
  u.role_key,'enabled',false,now(),0,now(),now()
FROM users u CROSS JOIN app_user a WHERE a.account='PERFADMIN'
ON CONFLICT(account) DO UPDATE SET role_key=EXCLUDED.role_key,password_hash=EXCLUDED.password_hash,
  status='enabled',must_change_password=false,updated_at=now();
