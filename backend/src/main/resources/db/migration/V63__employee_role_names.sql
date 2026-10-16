-- Preserve existing employee memberships and permissions; only the display name changes.
UPDATE app_role SET display_name = '业务-员工' WHERE role_key = 'employee';

-- Operations staff are available for account assignment but have no business access yet.
INSERT INTO app_role(role_key, display_name, description)
VALUES ('operations_employee', '运营-员工', '暂未分配业务权限');
