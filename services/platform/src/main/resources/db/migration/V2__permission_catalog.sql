CREATE TABLE permission_catalog(code varchar(64) PRIMARY KEY, name varchar(64) NOT NULL, global_only boolean NOT NULL);
INSERT INTO permission_catalog VALUES
 ('users.manage','管理账号与角色',true),('notifications.manage','管理全平台通知投递',true),
 ('spaces.read','读取空间目录',false),('spaces.write','维护本院系空间',false),('audit.read','查询本院系审计',false);
ALTER TABLE role_permissions ADD CONSTRAINT registered_permission FOREIGN KEY(permission) REFERENCES permission_catalog(code);
