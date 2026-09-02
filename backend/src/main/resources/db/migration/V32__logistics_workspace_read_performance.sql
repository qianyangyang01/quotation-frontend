ALTER TABLE logistics_version
    ADD COLUMN workspace_payload JSONB
        GENERATED ALWAYS AS (payload - 'rows' - 'issues' - 'diffRows') STORED,
    ADD COLUMN row_count INTEGER
        GENERATED ALWAYS AS (jsonb_array_length(COALESCE(payload->'rows','[]'::jsonb))) STORED,
    ADD COLUMN issue_count INTEGER
        GENERATED ALWAYS AS (jsonb_array_length(COALESCE(payload->'issues','[]'::jsonb))) STORED;
