ALTER TABLE idempotency_record
    ALTER COLUMN request_hash TYPE VARCHAR(64)
    USING TRIM(request_hash);
