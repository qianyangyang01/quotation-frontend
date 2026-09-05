\set ON_ERROR_STOP on
-- Test-only operational tuning. This is not a production migration.
DO $$ BEGIN
  IF current_database() <> 'quotation_perf' THEN
    RAISE EXCEPTION 'Only the isolated quotation_perf database is allowed';
  END IF;
END $$;
-- Keep new-row GIN pending entries from making the planner prefer a full scan.
-- Default is 4096 kB; test query latency and write overhead together.
ALTER INDEX idx_purchase_product_sku_trgm SET (gin_pending_list_limit = 256);
ALTER INDEX idx_purchase_product_payload_trgm SET (gin_pending_list_limit = 256);
VACUUM (ANALYZE) purchase_product;
