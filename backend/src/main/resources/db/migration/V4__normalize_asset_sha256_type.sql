ALTER TABLE asset_object
    ALTER COLUMN sha256 TYPE VARCHAR(64)
    USING TRIM(sha256);

ALTER TABLE import_part
    ALTER COLUMN sha256 TYPE VARCHAR(64)
    USING TRIM(sha256);

ALTER TABLE migration_manifest_entry
    ALTER COLUMN expected_sha256 TYPE VARCHAR(64)
    USING TRIM(expected_sha256);
