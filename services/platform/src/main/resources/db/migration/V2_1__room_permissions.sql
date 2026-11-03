-- Version 2.1 leaves the remote M1 branch V3-V5 in increasing migration order.
-- M2 permissions: membership alone does not confer application or approval eligibility.
INSERT INTO permission_catalog VALUES
 ('rooms.read','查看本人教室申请',false),
 ('rooms.apply','提交本院系教室申请',false),
 ('rooms.review','本院系教室申请初审',false),
 ('rooms.approve','本院系教室申请终审',false),
 ('rooms.manage','本院系申请恢复与撤销',false);
INSERT INTO roles(code,name) VALUES
 ('ROOM_APPLICANT','教室申请人'),('ROOM_REVIEWER','院系审核员'),('ROOM_APPROVER','教室审批员');
INSERT INTO role_permissions(role_code,permission)
 SELECT code,'rooms.read' FROM roles;
INSERT INTO role_permissions VALUES
 ('ROOM_APPLICANT','spaces.read'),('ROOM_APPLICANT','rooms.apply'),
 ('ROOM_REVIEWER','spaces.read'),('ROOM_REVIEWER','rooms.review'),
 ('ROOM_APPROVER','spaces.read'),('ROOM_APPROVER','rooms.approve'),('ROOM_APPROVER','rooms.manage');
INSERT INTO role_permissions(role_code,permission)
 SELECT 'ADMIN',code FROM permission_catalog WHERE code LIKE 'rooms.%' AND code<>'rooms.read';
