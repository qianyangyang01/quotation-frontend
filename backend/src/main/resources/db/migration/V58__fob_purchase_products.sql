CREATE TABLE fob_purchase_product (
    sku varchar(96) PRIMARY KEY,
    payload jsonb NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
