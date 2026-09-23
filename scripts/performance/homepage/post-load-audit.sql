\set ON_ERROR_STOP on
-- Read-only audit. Run after monitor.mjs has completed all five stages.
DO $$
DECLARE active_jobs bigint; duplicate_quotes bigint; enabled_test_providers bigint; interrupted_reviews bigint;
BEGIN
  IF current_database()<>'quotation_perf' THEN RAISE EXCEPTION 'Isolated database only'; END IF;
  SELECT (SELECT count(*) FROM logistics_import_batch WHERE status IN ('queued','processing')) +
    (SELECT count(*) FROM import_job WHERE status IN ('queued','parsing','import-queued','importing','rollback-queued','rolling-back')) INTO active_jobs;
  IF active_jobs<>0 THEN RAISE EXCEPTION 'Unfinished import jobs: %',active_jobs; END IF;
  SELECT count(*) FROM (SELECT payload->>'customerName' FROM quotation_record
    WHERE payload->>'customerName' ~ '^QA-[0-9A-Z]+-LOAD[0-9]{3}-[0-9]+$'
    GROUP BY payload->>'customerName' HAVING count(*)>1) duplicates INTO duplicate_quotes;
  IF duplicate_quotes<>0 THEN RAISE EXCEPTION 'Duplicate load submission identities: %',duplicate_quotes; END IF;
  SELECT count(*) FROM logistics_provider WHERE payload->>'name' LIKE '隔离导入%' AND coalesce(payload->>'enabled','true')<>'false' INTO enabled_test_providers;
  IF enabled_test_providers<>0 THEN RAISE EXCEPTION 'Test providers are enabled: %',enabled_test_providers; END IF;
  SELECT count(*) FROM quotation_review r JOIN quotation_record q ON q.id=r.id
    WHERE q.payload->>'customerName' ~ '^QA-[0-9A-Z]+-LOAD[0-9]{3}-[0-9]+$' AND r.status='reviewing' INTO interrupted_reviews;
  IF interrupted_reviews<>0 THEN RAISE EXCEPTION 'Interrupted load review transactions: %',interrupted_reviews; END IF;
END $$;
SELECT 'load-quotes',count(*) FROM quotation_record WHERE payload->>'customerName' ~ '^QA-[0-9A-Z]+-LOAD[0-9]{3}-[0-9]+$';
SELECT 'load-review-states',coalesce(r.status,'pending'),count(*) FROM quotation_record q LEFT JOIN quotation_review r ON r.id=q.id
  WHERE q.payload->>'customerName' ~ '^QA-[0-9A-Z]+-LOAD[0-9]{3}-[0-9]+$' GROUP BY r.status ORDER BY r.status;
SELECT 'logistics-import-states',status,count(*) FROM logistics_import_batch GROUP BY status ORDER BY status;
SELECT 'purchase-import-states',status,count(*) FROM import_job GROUP BY status ORDER BY status;
SELECT 'database-size',pg_size_pretty(pg_database_size(current_database()));
