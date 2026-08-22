CREATE TABLE IF NOT EXISTS app_role (
    role_key VARCHAR(40) PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL
);
CREATE TABLE IF NOT EXISTS role_permission (
    role_key VARCHAR(40) NOT NULL,
    permission_key VARCHAR(80) NOT NULL,
    PRIMARY KEY (role_key, permission_key)
);
MERGE INTO app_role KEY(role_key) VALUES
 ('super_admin','超级管理员'),('finance','财务'),('logistics','物流'),('purchase','采购'),('employee','员工');
MERGE INTO role_permission KEY(role_key, permission_key) VALUES
 ('super_admin','quote'),('super_admin','purchase'),('super_admin','logistics'),('super_admin','finance'),('super_admin','allRecords'),('super_admin','permissions'),
 ('finance','quote'),('finance','purchase'),('finance','logistics'),('finance','finance'),('finance','allRecords'),('finance','permissions'),
 ('logistics','logistics'),('purchase','purchase'),('employee','quote'),('employee','myRecords');
CREATE TABLE IF NOT EXISTS password_change_history (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    changed_by VARCHAR(24) NOT NULL,
    change_type VARCHAR(24) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
