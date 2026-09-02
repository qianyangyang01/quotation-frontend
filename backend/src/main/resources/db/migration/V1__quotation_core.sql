CREATE TABLE app_role (
    role_key VARCHAR(32) PRIMARY KEY,
    display_name VARCHAR(64) NOT NULL,
    description VARCHAR(255) NOT NULL
);

CREATE TABLE role_permission (
    role_key VARCHAR(32) NOT NULL REFERENCES app_role(role_key),
    permission_key VARCHAR(32) NOT NULL,
    PRIMARY KEY (role_key, permission_key)
);

CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    account VARCHAR(24) NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(120) NOT NULL,
    role_key VARCHAR(32) NOT NULL REFERENCES app_role(role_key),
    status VARCHAR(16) NOT NULL,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    password_updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE purchase_product (
    id UUID PRIMARY KEY,
    sku VARCHAR(96) NOT NULL UNIQUE,
    payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_purchase_product_updated ON purchase_product(updated_at DESC);

CREATE TABLE asset_object (
    id UUID PRIMARY KEY,
    sha256 CHAR(64) NOT NULL UNIQUE,
    object_key VARCHAR(512) NOT NULL UNIQUE,
    media_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    original_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE purchase_product_image (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES purchase_product(id) ON DELETE CASCADE,
    asset_id UUID NOT NULL REFERENCES asset_object(id),
    image_type VARCHAR(24) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    UNIQUE (product_id, asset_id, image_type)
);

CREATE TABLE logistics_provider (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE logistics_channel (
    id UUID PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES logistics_provider(id),
    code VARCHAR(96) NOT NULL UNIQUE,
    rule_id INTEGER NOT NULL UNIQUE,
    current_version_id UUID,
    payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE logistics_version (
    id UUID PRIMARY KEY,
    channel_id UUID NOT NULL REFERENCES logistics_channel(id),
    version_number INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    source_hash VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    UNIQUE (channel_id, version_number),
    UNIQUE (channel_id, source_hash)
);
ALTER TABLE logistics_channel ADD CONSTRAINT fk_logistics_current_version FOREIGN KEY (current_version_id) REFERENCES logistics_version(id);

CREATE TABLE finance_setting (
    setting_key VARCHAR(64) PRIMARY KEY,
    payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE quotation_record (
    id UUID PRIMARY KEY,
    quote_no VARCHAR(40) NOT NULL UNIQUE,
    owner_account VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL,
    payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_quotation_owner_created ON quotation_record(owner_account, created_at DESC);
CREATE INDEX idx_quotation_status_created ON quotation_record(status, created_at DESC);

CREATE TABLE quotation_draft (
    owner_account VARCHAR(24) PRIMARY KEY,
    payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE quotation_template (
    id UUID PRIMARY KEY,
    owner_account VARCHAR(24) NOT NULL,
    name VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_template_owner_updated ON quotation_template(owner_account, updated_at DESC);

CREATE TABLE import_job (
    id UUID PRIMARY KEY,
    job_type VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    requested_by VARCHAR(24) NOT NULL,
    source_name VARCHAR(255) NOT NULL,
    source_hash VARCHAR(64),
    payload JSONB NOT NULL,
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);
CREATE INDEX idx_import_job_status ON import_job(status, updated_at DESC);

CREATE TABLE import_part (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES import_job(id) ON DELETE CASCADE,
    part_number INTEGER NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (job_id, part_number)
);

CREATE TABLE idempotency_record (
    id UUID PRIMARY KEY,
    account VARCHAR(24) NOT NULL,
    operation VARCHAR(96) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_status INTEGER NOT NULL,
    response_body JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (account, operation, idempotency_key)
);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    actor_account VARCHAR(24) NOT NULL,
    action VARCHAR(96) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(120),
    outcome VARCHAR(24) NOT NULL,
    detail JSONB NOT NULL,
    ip_address VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_audit_created ON audit_log(created_at DESC);
CREATE INDEX idx_audit_actor ON audit_log(actor_account, created_at DESC);

INSERT INTO app_role(role_key, display_name, description) VALUES
 ('super_admin', '超级管理员', '维护全部报价业务和账号权限'),
 ('finance', '财务', '保持现有财务角色的完整业务权限'),
 ('logistics', '物流', '维护物流商、渠道和价格版本'),
 ('purchase', '采购', '维护采购商品和导入数据'),
 ('employee', '员工', '发起本人报价并查看本人记录');

INSERT INTO role_permission(role_key, permission_key) VALUES
 ('super_admin','quote'),('super_admin','purchase'),('super_admin','logistics'),('super_admin','finance'),('super_admin','allRecords'),('super_admin','permissions'),
 ('finance','quote'),('finance','purchase'),('finance','logistics'),('finance','finance'),('finance','allRecords'),('finance','permissions'),
 ('logistics','logistics'),('purchase','purchase'),('employee','quote'),('employee','myRecords');
