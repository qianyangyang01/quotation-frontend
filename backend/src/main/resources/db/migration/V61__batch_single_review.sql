-- Review metadata and explicit revisions only. Original documents remain append-only.
create table batch_single_review (
    record_id uuid primary key references batch_single_quotation(id),
    version bigint not null,
    state jsonb not null,
    updated_at timestamptz not null default now()
);
