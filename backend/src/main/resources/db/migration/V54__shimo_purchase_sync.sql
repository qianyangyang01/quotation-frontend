-- Additive only: rolling the application back does not remove business data.
CREATE TABLE shimo_sync_control (
    id integer PRIMARY KEY CHECK (id = 1),
    enabled boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO shimo_sync_control(id) VALUES (1);
CREATE TABLE shimo_sync_run (
    id uuid PRIMARY KEY,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    status varchar(32) NOT NULL,
    mode varchar(24) NOT NULL DEFAULT 'manual',
    schedule_day date,
    reason text NOT NULL DEFAULT '',
    processed integer NOT NULL DEFAULT 0,
    changed integer NOT NULL DEFAULT 0
);
ALTER TABLE shimo_sync_run ADD COLUMN current_sheet text NOT NULL DEFAULT '';
ALTER TABLE shimo_sync_run ADD COLUMN next_row integer NOT NULL DEFAULT 2;
CREATE INDEX shimo_sync_run_started_idx ON shimo_sync_run(started_at DESC);
CREATE INDEX shimo_sync_run_schedule_idx ON shimo_sync_run(schedule_day, mode, status);
CREATE TABLE shimo_sync_fetch_sheet (
    run_id uuid NOT NULL REFERENCES shimo_sync_run(id),
    sheet text NOT NULL,
    index_hash varchar(64) NOT NULL,
    PRIMARY KEY(run_id,sheet)
);
CREATE TABLE shimo_sync_fetch_page (
    run_id uuid NOT NULL REFERENCES shimo_sync_run(id),
    sheet text NOT NULL,
    first_row integer NOT NULL,
    last_row integer NOT NULL,
    payload jsonb NOT NULL,
    fetched_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(run_id,sheet,first_row,last_row)
);
CREATE TABLE shimo_sync_item (
    sku varchar(96) PRIMARY KEY,
    sheet text NOT NULL,
    source_row integer NOT NULL,
    status varchar(32) NOT NULL,
    reason text NOT NULL DEFAULT '',
    source_hash varchar(64) NOT NULL DEFAULT '',
    applied_hash varchar(64) NOT NULL DEFAULT '',
    product_id uuid,
    product_version bigint,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    checked_at timestamptz NOT NULL DEFAULT now(),
    synced_at timestamptz,
    run_id uuid NOT NULL REFERENCES shimo_sync_run(id)
);
CREATE INDEX shimo_sync_item_status_idx ON shimo_sync_item(status, checked_at);
CREATE TABLE shimo_sync_change (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES shimo_sync_run(id),
    sku varchar(96) NOT NULL,
    sheet text NOT NULL,
    source_row integer NOT NULL,
    product_id uuid NOT NULL,
    before_payload jsonb,
    after_payload jsonb NOT NULL,
    after_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    reverted_at timestamptz
);
CREATE INDEX shimo_sync_change_run_idx ON shimo_sync_change(run_id, created_at);
CREATE INDEX shimo_sync_change_created_idx ON shimo_sync_change(created_at DESC, id);
