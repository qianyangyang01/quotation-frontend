-- Schema only: initialization and destructive rebuilding are explicit audited operations.
CREATE TABLE logistics_company_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    revision bigint NOT NULL DEFAULT 0,
    enabled boolean NOT NULL DEFAULT false,
    paused boolean NOT NULL DEFAULT false,
    rebuild_id uuid,
    target_dataset_id uuid REFERENCES logistics_dataset(id)
);
INSERT INTO logistics_company_state(singleton) VALUES(true);
CREATE TABLE logistics_company_channel (
    id uuid PRIMARY KEY,
    enabled boolean NOT NULL,
    payload jsonb NOT NULL,
    updated_by text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE logistics_company_revision (
    revision bigint PRIMARY KEY,
    payload jsonb NOT NULL,
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE logistics_company_binding (
    channel_id uuid PRIMARY KEY REFERENCES logistics_channel(id),
    company_channel_id uuid NOT NULL REFERENCES logistics_company_channel(id),
    dataset_id uuid NOT NULL REFERENCES logistics_dataset(id),
    UNIQUE(dataset_id,company_channel_id)
);
CREATE TABLE logistics_company_rebuild (
    id uuid PRIMARY KEY,
    phase text NOT NULL,
    payload jsonb NOT NULL,
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE logistics_quotation_history (
    quotation_id uuid PRIMARY KEY REFERENCES quotation_record(id),
    snapshot jsonb NOT NULL,
    captured_at timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION logistics_company_allowed(selected_channel uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT NOT s.enabled OR EXISTS(
      SELECT 1 FROM logistics_company_binding b JOIN logistics_company_channel d ON d.id=b.company_channel_id
      WHERE b.channel_id=selected_channel AND d.enabled)
    FROM logistics_company_state s WHERE singleton
$$;

CREATE FUNCTION logistics_company_quote_allowed(selected_channel uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT NOT paused AND logistics_company_allowed(selected_channel) FROM logistics_company_state WHERE singleton
$$;

CREATE FUNCTION logistics_company_write_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE state logistics_company_state; selected_dataset uuid; selected_channel uuid; job logistics_company_rebuild;
BEGIN
    SELECT * INTO state FROM logistics_company_state WHERE singleton FOR SHARE;
    IF TG_TABLE_NAME='logistics_version' THEN
        selected_channel:=coalesce(NEW.channel_id,OLD.channel_id);
        SELECT dataset_id INTO selected_dataset FROM logistics_channel WHERE id=selected_channel;
    ELSE selected_dataset:=coalesce(NEW.dataset_id,OLD.dataset_id); END IF;
    IF state.paused AND selected_dataset=state.target_dataset_id THEN
      IF NOT EXISTS(SELECT 1 FROM logistics_company_rebuild WHERE id=state.rebuild_id AND phase='deleted') THEN
        RAISE EXCEPTION '请先完成旧价格备份和物理清理';
      END IF;
    END IF;
    IF state.paused AND selected_dataset IS DISTINCT FROM state.target_dataset_id THEN
        SELECT * INTO job FROM logistics_company_rebuild WHERE id=state.rebuild_id;
        IF NOT (job.phase IN ('deleting','restoring') AND current_setting('app.logistics_purge_job',true)=job.id::text
          AND job.payload->'sourceDatasets' @> to_jsonb(ARRAY[selected_dataset::text])) THEN
          RAISE EXCEPTION '物流重建中，旧价格暂停写入';
        END IF;
        IF job.phase='restoring' THEN
          IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
        END IF;
    END IF;
    IF TG_TABLE_NAME='logistics_version' AND TG_OP<>'DELETE' THEN
      IF NEW.status IN ('draft','published') AND NOT logistics_company_allowed(selected_channel) THEN
        RAISE EXCEPTION '渠道未进入公司清单或已经停用';
      END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER logistics_company_provider_guard BEFORE INSERT OR UPDATE OR DELETE ON logistics_provider FOR EACH ROW EXECUTE FUNCTION logistics_company_write_guard();
CREATE TRIGGER logistics_company_channel_guard BEFORE INSERT OR UPDATE OR DELETE ON logistics_channel FOR EACH ROW EXECUTE FUNCTION logistics_company_write_guard();
CREATE TRIGGER logistics_company_version_guard BEFORE INSERT OR UPDATE OR DELETE ON logistics_version FOR EACH ROW EXECUTE FUNCTION logistics_company_write_guard();

-- Keep archived datasets immutable except for the exact, frozen purge job.
CREATE OR REPLACE FUNCTION logistics_guard_dataset_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE selected_dataset uuid; dataset_status text; purge boolean;
BEGIN
    IF TG_TABLE_NAME='logistics_version' THEN
      SELECT dataset_id INTO selected_dataset FROM logistics_channel WHERE id=coalesce(NEW.channel_id,OLD.channel_id);
    ELSE
      selected_dataset:=coalesce(NEW.dataset_id,OLD.dataset_id);
      IF TG_OP='UPDATE' AND NEW.dataset_id<>OLD.dataset_id THEN RAISE EXCEPTION 'Logistics dataset identity is immutable'; END IF;
    END IF;
    SELECT status INTO dataset_status FROM logistics_dataset WHERE id=selected_dataset FOR SHARE;
    IF dataset_status='archived' THEN
      SELECT EXISTS(SELECT 1 FROM logistics_company_rebuild j JOIN logistics_company_state s ON s.rebuild_id=j.id
        WHERE s.paused AND j.phase IN ('deleting','restoring') AND j.id::text=current_setting('app.logistics_purge_job',true)
        AND j.payload->'sourceDatasets' @> to_jsonb(ARRAY[selected_dataset::text])) INTO purge;
      IF NOT purge THEN RAISE EXCEPTION 'Archived logistics dataset is read-only'; END IF;
      IF EXISTS(SELECT 1 FROM logistics_company_rebuild WHERE id::text=current_setting('app.logistics_purge_job',true) AND phase='restoring') THEN
        IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
      END IF;
      IF TG_OP<>'DELETE' THEN
        IF TG_TABLE_NAME='logistics_channel' AND TG_OP='UPDATE' THEN
          IF NEW.current_version_id IS NOT NULL THEN RAISE EXCEPTION 'Only clearing current version is allowed'; END IF;
        ELSE RAISE EXCEPTION 'Archived logistics dataset is read-only'; END IF;
      END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
