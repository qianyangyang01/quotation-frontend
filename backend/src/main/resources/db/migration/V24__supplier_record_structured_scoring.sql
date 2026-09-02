ALTER TABLE supplier_record
    ADD COLUMN price_level VARCHAR(20),
    ADD COLUMN after_sales_available BOOLEAN,
    ADD COLUMN calculated_score INTEGER
        CHECK (calculated_score IS NULL OR (calculated_score >= 0 AND calculated_score <= 100)),
    ADD COLUMN score_policy_version VARCHAR(40);
