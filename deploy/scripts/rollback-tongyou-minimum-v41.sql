-- Explicit rollback of this correction only; keeps both versions and audit evidence.
BEGIN;
DO $$
DECLARE item record;
BEGIN
  IF (SELECT count(*) FROM audit_log WHERE action='logistics.minimum-weight.complete'
      AND detail->>'repairId'='tongyou-sensitive-b-minimum-20260916')<>1
  THEN RAISE EXCEPTION 'Expected exactly one Tongyou correction'; END IF;
  SELECT resource_id::uuid AS channel_id,detail INTO STRICT item FROM audit_log
  WHERE action='logistics.minimum-weight.complete' AND detail->>'repairId'='tongyou-sensitive-b-minimum-20260916';
  PERFORM 1 FROM logistics_channel c JOIN logistics_version v ON v.id=c.current_version_id
    JOIN logistics_version old ON old.id=(item.detail->>'beforeVersionId')::uuid
  WHERE c.id=item.channel_id AND c.dataset_id=logistics_active_dataset() AND c.archived_at IS NULL
    AND v.id=(item.detail->>'afterVersionId')::uuid AND v.status='published'
    AND v.rows_fingerprint=item.detail->>'afterRowsFingerprint'
    AND old.status='superseded' AND old.channel_id=c.id
    AND old.rows_fingerprint=item.detail->>'beforeRowsFingerprint'
  FOR UPDATE OF c,v,old;
  IF NOT FOUND THEN RAISE EXCEPTION 'Tongyou channel changed after correction'; END IF;
  UPDATE logistics_version SET status='superseded',payload=jsonb_set(payload,'{status}','"superseded"')
    WHERE id=(item.detail->>'afterVersionId')::uuid;
  UPDATE logistics_version SET status='published',payload=jsonb_set(payload,'{status}','"published"')
    WHERE id=(item.detail->>'beforeVersionId')::uuid;
  UPDATE logistics_channel SET current_version_id=(item.detail->>'beforeVersionId')::uuid,
    version=version+1,updated_at=now(),payload=payload||jsonb_build_object('currentVersionId',item.detail->>'beforeVersionId','updatedAt',now())
    WHERE id=item.channel_id;
  IF NOT logistics_version_quote_ready((item.detail->>'beforeVersionId')::uuid)
  THEN RAISE EXCEPTION 'Previous version is not quote ready'; END IF;
  INSERT INTO audit_log(id,request_id,actor_account,action,resource_type,resource_id,outcome,detail,created_at)
  VALUES(gen_random_uuid(),'tongyou-sensitive-b-minimum-20260916','rollback-v41','logistics.minimum-weight.rollback',
    'logistics-channel',item.channel_id::text,'success',item.detail,now());
END $$;
COMMIT;
