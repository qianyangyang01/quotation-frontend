-- Isolated append-only documents. No update of ordinary or historical quotation rows.
create table batch_single_quotation (
    id uuid primary key,
    quote_no varchar(64) not null unique,
    owner_account varchar(120) not null,
    created_at timestamptz not null,
    payload jsonb not null
);
create index batch_single_quotation_owner_created on batch_single_quotation(owner_account, created_at desc);
create index batch_single_quotation_created on batch_single_quotation(created_at desc);
