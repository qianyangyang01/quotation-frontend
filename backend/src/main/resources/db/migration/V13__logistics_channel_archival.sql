alter table logistics_channel
    add column if not exists archived_at timestamptz,
    add column if not exists archived_by varchar(120),
    add column if not exists archive_reason varchar(500);

create index if not exists idx_logistics_channel_provider_archived_updated
    on logistics_channel(provider_id, archived_at, updated_at desc);
