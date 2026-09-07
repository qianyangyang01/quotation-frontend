CREATE TABLE logistics_upload_session (
    id uuid PRIMARY KEY,
    dataset_id uuid NOT NULL REFERENCES logistics_dataset(id),
    actor varchar(160) NOT NULL,
    request_key varchar(120) NOT NULL,
    manifest text NOT NULL,
    received text NOT NULL,
    batch_id uuid REFERENCES logistics_import_batch(id),
    chunks_cleaned boolean NOT NULL DEFAULT false,
    expires_at timestamptz NOT NULL DEFAULT now() + interval '7 days',
    UNIQUE(actor, request_key)
);
CREATE INDEX logistics_upload_expiry_idx ON logistics_upload_session(expires_at);
