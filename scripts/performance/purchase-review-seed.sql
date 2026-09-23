\set ON_ERROR_STOP on
DO $$ BEGIN IF current_database()<>'quotation_perf' THEN RAISE EXCEPTION 'isolated database only'; END IF; END $$;
INSERT INTO app_user(id,account,display_name,password_hash,role_key,status,must_change_password,password_updated_at,version,created_at,updated_at)
SELECT md5('sync-user-'||n)::uuid,'SYNC'||lpad(n::text,3,'0'),'同步测试'||n,a.password_hash,
CASE WHEN (n-1)%5<3 THEN 'employee' WHEN (n-1)%5=3 THEN 'purchase' ELSE 'super_admin' END,
'enabled',false,now(),0,now(),now() FROM generate_series(1,100)n CROSS JOIN app_user a WHERE a.account='PERFADMIN';
INSERT INTO quotation_record(id,quote_no,owner_account,status,payload,version,created_at,updated_at)
SELECT md5('sync-quote-'||u.account||'-'||n)::uuid,'SYNQ'||u.account||'-'||n,u.account,'pending',
jsonb_build_object('id',md5('sync-quote-'||u.account||'-'||n)::uuid,'no','SYNQ'||u.account||'-'||n,'salespersonAccount',u.account,'salespersonName',u.display_name,'customerName','隔离同步客户','primarySku','PERF-SKU-00001','productCategory','服装','status','pending','quoteOptions',jsonb_build_array(jsonb_build_object('id','o1','country','美国','channel','性能普货专线','quote1Usd',10)),'revisions','[]'::jsonb),0,now(),now()
FROM app_user u CROSS JOIN generate_series(1,10)n WHERE u.account LIKE 'SYNC%' AND u.role_key='employee';
INSERT INTO quotation_draft(owner_account,payload,version,updated_at)
SELECT account,'{"schemaVersion":2,"customerName":"同步压测保护草稿","skuSearch":"PERF-SKU-00001","commissionThreshold":1}'::jsonb,0,now() FROM app_user WHERE account LIKE 'SYNC%' AND role_key='employee';
ANALYZE purchase_product;
ANALYZE quotation_record;
