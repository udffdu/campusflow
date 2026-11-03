-- Additive metadata for M1. Never grant new business privileges to existing users implicitly.
INSERT INTO permission_catalog(code,name,global_only) VALUES
 ('seats.availability.read','查询可预约座位',false),
 ('seats.reservations.create','创建本人座位预约',false),
 ('seats.reservations.read.own','读取本人座位预约',false);
INSERT INTO roles(code,name) VALUES ('SEAT_USER','座位预约用户');
INSERT INTO role_permissions(role_code,permission) VALUES
 ('SEAT_USER','seats.availability.read'),
 ('SEAT_USER','seats.reservations.create'),
 ('SEAT_USER','seats.reservations.read.own');

ALTER TABLE resources ADD COLUMN booking_scope varchar(16);
UPDATE resources SET booking_scope='DEPARTMENT', version=nextval('resource_version'), updated_at=now()
 WHERE type='ROOM';
-- NULL remains accepted for old D binaries inserting rooms without the new column.
-- The new API normalizes a legacy room's NULL to DEPARTMENT. Other resource types stay NULL.
ALTER TABLE resources ADD CONSTRAINT room_booking_scope
 CHECK (booking_scope IS NULL OR (type='ROOM' AND booking_scope IN ('DEPARTMENT','CAMPUS')));
-- Existing free-text features are not rewritten: outlet metadata requires an explicit seat marker.
