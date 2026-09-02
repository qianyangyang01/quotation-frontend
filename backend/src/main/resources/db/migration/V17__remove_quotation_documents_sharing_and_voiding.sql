CREATE TABLE quotation_void_state_backup_v17 (
    quotation_id UUID PRIMARY KEY,
    previous_status VARCHAR(16) NOT NULL,
    had_payload_status BOOLEAN NOT NULL,
    previous_payload_status JSONB,
    had_status_before_void BOOLEAN NOT NULL,
    previous_status_before_void JSONB,
    previous_voided_at TIMESTAMPTZ,
    previous_voided_by VARCHAR(24),
    previous_void_reason VARCHAR(500),
    previous_version BIGINT NOT NULL,
    normalized_status VARCHAR(16) NOT NULL,
    normalized_payload_status JSONB,
    backed_up_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    restored_at TIMESTAMPTZ
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM quotation_record
        WHERE (status = 'voided' OR payload ->> 'status' = 'voided')
          AND (
              NOT (payload ? '_statusBeforeVoid')
              OR jsonb_typeof(payload -> '_statusBeforeVoid') <> 'string'
              OR payload ->> '_statusBeforeVoid' NOT IN ('pending', 'won', 'lost')
          )
    ) THEN
        RAISE EXCEPTION 'V17 cannot restore a voided quotation without a valid _statusBeforeVoid value';
    END IF;
END $$;

INSERT INTO quotation_void_state_backup_v17 (
    quotation_id,
    previous_status,
    had_payload_status,
    previous_payload_status,
    had_status_before_void,
    previous_status_before_void,
    previous_voided_at,
    previous_voided_by,
    previous_void_reason,
    previous_version,
    normalized_status,
    normalized_payload_status
)
SELECT
    id,
    status,
    payload ? 'status',
    payload -> 'status',
    payload ? '_statusBeforeVoid',
    payload -> '_statusBeforeVoid',
    voided_at,
    voided_by,
    void_reason,
    version,
    CASE
        WHEN status = 'voided' OR payload ->> 'status' = 'voided' THEN payload ->> '_statusBeforeVoid'
        ELSE status
    END,
    CASE
        WHEN status = 'voided' OR payload ->> 'status' = 'voided' THEN to_jsonb(payload ->> '_statusBeforeVoid')
        ELSE payload -> 'status'
    END
FROM quotation_record
WHERE status = 'voided'
   OR payload ->> 'status' = 'voided'
   OR payload ? '_statusBeforeVoid'
   OR voided_at IS NOT NULL
   OR voided_by IS NOT NULL
   OR void_reason IS NOT NULL;

UPDATE quotation_record quotation
SET status = backup.normalized_status,
    payload = CASE
        WHEN backup.normalized_payload_status IS NULL THEN quotation.payload - '_statusBeforeVoid' - 'status'
        ELSE jsonb_set(quotation.payload - '_statusBeforeVoid', '{status}', backup.normalized_payload_status, true)
    END
FROM quotation_void_state_backup_v17 backup
WHERE quotation.id = backup.quotation_id;

DROP TABLE quotation_share;

ALTER TABLE quotation_record
    DROP COLUMN voided_at,
    DROP COLUMN voided_by,
    DROP COLUMN void_reason;

COMMENT ON TABLE quotation_void_state_backup_v17 IS
    'V17 rollback-only mapping. Application code must not read or write this table; remove after the observation period.';
