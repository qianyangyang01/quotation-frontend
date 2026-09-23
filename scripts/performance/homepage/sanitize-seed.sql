\set ON_ERROR_STOP on
DO $$ BEGIN IF current_database()<>'quotation_perf' THEN RAISE EXCEPTION 'Only quotation_perf is allowed'; END IF; END $$;
BEGIN;
CREATE FUNCTION pg_temp.qa_scrub(value jsonb) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE result jsonb; key text; child jsonb;
BEGIN
 IF jsonb_typeof(value)='object' THEN
  result='{}'::jsonb;
  FOR key,child IN SELECT * FROM jsonb_each(value) LOOP
   IF key=ANY(ARRAY['customerName','salespersonName','salespersonAccount','quotationOwner','ownerName','ownerAccount','ownerKey','financeReviewedBy','financeReviewedAccount','financeReviewClaimedBy','financeReviewClaimedAccount','editorName','editorAccount','createdBy','updatedBy','contact','phone','email','address','name']) AND jsonb_typeof(child)='string' THEN
    child=to_jsonb('QA-'||substr(md5(child::text),1,12));
   ELSIF key=ANY(ARRAY['productImage','physicalImage','image','factoryInfo','notes','note','sourceLinks','url','link']) THEN
    child=CASE WHEN key='sourceLinks' THEN '[]'::jsonb ELSE '""'::jsonb END;
   ELSE child=pg_temp.qa_scrub(child);
   END IF;
   result=result||jsonb_build_object(key,child);
  END LOOP;
  RETURN result;
 ELSIF jsonb_typeof(value)='array' THEN
  SELECT coalesce(jsonb_agg(pg_temp.qa_scrub(v)),'[]'::jsonb) INTO result FROM jsonb_array_elements(value) v;
  RETURN result;
 END IF;
 RETURN value;
END $$;
TRUNCATE audit_log,idempotency_record,password_change_history;
UPDATE app_user SET display_name='QA-'||substr(md5(account),1,12),password_hash='DISABLED-SNAPSHOT-NO-LOGIN',status='disabled';
UPDATE purchase_product SET payload=pg_temp.qa_scrub(payload);
UPDATE quotation_record SET payload=pg_temp.qa_scrub(payload);
UPDATE quotation_template SET payload=pg_temp.qa_scrub(payload),name='QA template';
UPDATE quotation_draft SET payload=pg_temp.qa_scrub(payload);
UPDATE quotation_review SET state=pg_temp.qa_scrub(state);
UPDATE import_job SET status=CASE WHEN status IN ('queued','processing') THEN 'failed' ELSE status END;
UPDATE logistics_import_batch SET status=CASE WHEN status IN ('queued','processing') THEN 'failed' ELSE status END;
WITH users AS (
 SELECT 'LOAD'||lpad(n::text,3,'0') account,
 CASE WHEN ((n-1)%50)<30 THEN 'employee' WHEN ((n-1)%50)<40 THEN 'purchase' WHEN ((n-1)%50)<44 THEN 'finance' WHEN ((n-1)%50)<48 THEN 'logistics' ELSE 'super_admin' END role_key
 FROM generate_series(1,100)n
 UNION ALL VALUES ('PERFADMIN','super_admin'),('PERFFIN','finance'),('PERFPUR','purchase'),('PERFLOG','logistics')
)
INSERT INTO app_user(id,account,display_name,password_hash,role_key,status,must_change_password,password_updated_at,version,created_at,updated_at)
SELECT md5('homepage-qa-'||account)::uuid,account,'隔离测试'||account,
'$2a$10$1NOnPrOuW1ElZG5ouBJXeO01jgBFgdWWiKEz7dnFbWyUC3wXNhsOO',role_key,'enabled',false,now(),0,now(),now() FROM users
ON CONFLICT(account) DO UPDATE SET password_hash=excluded.password_hash,role_key=excluded.role_key,status='enabled',must_change_password=false;
COMMIT;
ANALYZE;
SELECT 'purchase',count(*) FROM purchase_product UNION ALL SELECT 'quotations',count(*) FROM quotation_record UNION ALL SELECT 'enabled-test-users',count(*) FROM app_user WHERE status='enabled';
