-- Per-account name shortcuts, deliberately independent of company finance customers and quotations.
CREATE TABLE personal_quotation_customer (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES app_user(id),
    name varchar(120) NOT NULL,
    normalized_name varchar(240) NOT NULL,
    updated_at timestamptz NOT NULL,
    last_used_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (user_id, normalized_name)
);
