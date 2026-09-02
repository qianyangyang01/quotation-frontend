DO $$
BEGIN
    IF to_regclass('public.quotation_void_state_backup_v17') IS NULL THEN
        RAISE EXCEPTION 'V17 quotation void-state backup table does not exist';
    END IF;
    IF EXISTS (SELECT 1 FROM quotation_void_state_backup_v17 WHERE restored_at IS NOT NULL) THEN
        RAISE EXCEPTION 'V17 quotation void-state backup has already been restored';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM quotation_void_state_backup_v17 backup
        LEFT JOIN quotation_record quotation ON quotation.id = backup.quotation_id
        WHERE quotation.id IS NULL
           OR quotation.version <> backup.previous_version
           OR quotation.status <> backup.normalized_status
           OR quotation.payload -> 'status' IS DISTINCT FROM backup.normalized_payload_status
           OR quotation.payload ? '_statusBeforeVoid'
    ) THEN
        RAISE EXCEPTION 'Quotation records changed after V17; refusing to overwrite newer data';
    END IF;
END $$;

ALTER TABLE quotation_record
    ADD COLUMN IF NOT EXISTS voided_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS voided_by VARCHAR(24),
    ADD COLUMN IF NOT EXISTS void_reason VARCHAR(500);

UPDATE quotation_record quotation
SET status = backup.previous_status,
    payload = CASE
        WHEN backup.had_status_before_void THEN
            jsonb_set(
                CASE
                    WHEN backup.had_payload_status THEN jsonb_set(quotation.payload, '{status}', backup.previous_payload_status, true)
                    ELSE quotation.payload - 'status'
                END,
                '{_statusBeforeVoid}', backup.previous_status_before_void, true
            )
        WHEN backup.had_payload_status THEN jsonb_set(quotation.payload - '_statusBeforeVoid', '{status}', backup.previous_payload_status, true)
        ELSE quotation.payload - '_statusBeforeVoid' - 'status'
    END,
    voided_at = backup.previous_voided_at,
    voided_by = backup.previous_voided_by,
    void_reason = backup.previous_void_reason
FROM quotation_void_state_backup_v17 backup
WHERE quotation.id = backup.quotation_id;

UPDATE quotation_void_state_backup_v17
SET restored_at = CURRENT_TIMESTAMP;
