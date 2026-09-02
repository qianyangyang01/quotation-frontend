-- Existing prices stay active. Creating this schema does not switch business data.
CREATE TABLE logistics_dataset (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('preparing','active','archived')),
    revision BIGINT NOT NULL DEFAULT 0,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX logistics_one_active_dataset ON logistics_dataset(status) WHERE status='active';
INSERT INTO logistics_dataset(id,name,status,created_by,activated_at)
VALUES ('00000000-0000-0000-0000-000000000001','原物流库','active','migration',now());

CREATE FUNCTION logistics_active_dataset() RETURNS UUID LANGUAGE sql STABLE AS
$$ SELECT id FROM logistics_dataset WHERE status='active' $$;

ALTER TABLE logistics_provider ADD COLUMN dataset_id UUID NOT NULL DEFAULT logistics_active_dataset() REFERENCES logistics_dataset(id);
ALTER TABLE logistics_channel ADD COLUMN dataset_id UUID NOT NULL DEFAULT logistics_active_dataset() REFERENCES logistics_dataset(id);
ALTER TABLE logistics_provider DROP CONSTRAINT logistics_provider_code_key;
ALTER TABLE logistics_channel DROP CONSTRAINT logistics_channel_code_key;
CREATE UNIQUE INDEX logistics_provider_dataset_code ON logistics_provider(dataset_id,lower(code));
CREATE UNIQUE INDEX logistics_channel_dataset_code ON logistics_channel(dataset_id,lower(code));
ALTER TABLE logistics_provider ADD CONSTRAINT logistics_provider_id_dataset UNIQUE(id,dataset_id);
ALTER TABLE logistics_channel ADD CONSTRAINT logistics_channel_provider_dataset
    FOREIGN KEY(provider_id,dataset_id) REFERENCES logistics_provider(id,dataset_id);
CREATE INDEX logistics_channel_dataset_updated ON logistics_channel(dataset_id,updated_at DESC);
CREATE SEQUENCE logistics_rule_identity;
SELECT setval('logistics_rule_identity',greatest(coalesce((SELECT max(rule_id) FROM logistics_channel),0)+1,1),false);

-- Database guards also cover legacy endpoints: an archived dataset is read-only.
CREATE FUNCTION logistics_guard_dataset_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE selected_dataset UUID; dataset_status TEXT;
BEGIN
    IF TG_TABLE_NAME='logistics_version' THEN
        SELECT dataset_id INTO selected_dataset FROM logistics_channel WHERE id=coalesce(NEW.channel_id,OLD.channel_id);
    ELSE
        selected_dataset := coalesce(NEW.dataset_id,OLD.dataset_id);
        IF TG_OP='UPDATE' AND NEW.dataset_id<>OLD.dataset_id THEN
            RAISE EXCEPTION 'Logistics dataset identity is immutable';
        END IF;
    END IF;
    -- Shared locks serialize writes with the exclusive dataset cutover lock.
    SELECT status INTO dataset_status FROM logistics_dataset WHERE id=selected_dataset FOR SHARE;
    IF dataset_status='archived' THEN RAISE EXCEPTION 'Archived logistics dataset is read-only'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER logistics_provider_dataset_guard BEFORE INSERT OR UPDATE OR DELETE ON logistics_provider FOR EACH ROW EXECUTE FUNCTION logistics_guard_dataset_write();
CREATE TRIGGER logistics_channel_dataset_guard BEFORE INSERT OR UPDATE OR DELETE ON logistics_channel FOR EACH ROW EXECUTE FUNCTION logistics_guard_dataset_write();
CREATE TRIGGER logistics_version_dataset_guard BEFORE INSERT OR UPDATE OR DELETE ON logistics_version FOR EACH ROW EXECUTE FUNCTION logistics_guard_dataset_write();

CREATE TABLE logistics_import_batch (
    id UUID PRIMARY KEY,
    dataset_id UUID NOT NULL REFERENCES logistics_dataset(id),
    requested_by VARCHAR(120) NOT NULL,
    request_key VARCHAR(160) NOT NULL,
    lease_id UUID,
    status VARCHAR(24) NOT NULL,
    phase VARCHAR(40) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(requested_by,request_key)
);
CREATE INDEX logistics_batch_dataset_created ON logistics_import_batch(dataset_id,created_at DESC);
