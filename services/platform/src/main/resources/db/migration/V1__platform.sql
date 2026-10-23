CREATE TABLE departments (id uuid PRIMARY KEY, code varchar(32) UNIQUE NOT NULL, name varchar(100) NOT NULL);
INSERT INTO departments VALUES
 ('10000000-0000-0000-0000-000000000001','EIE','信息工程学院'),
 ('10000000-0000-0000-0000-000000000002','SCI','理学院');

CREATE TABLE roles (code varchar(32) PRIMARY KEY, name varchar(64) NOT NULL);
INSERT INTO roles VALUES ('ADMIN','系统管理员'),('SPACE_MANAGER','空间管理员'),('AUDITOR','审计员'),('MEMBER','普通成员');
CREATE TABLE role_permissions (role_code varchar(32) REFERENCES roles(code), permission varchar(64), PRIMARY KEY(role_code, permission));
INSERT INTO role_permissions VALUES
 ('ADMIN','users.manage'),('ADMIN','spaces.read'),('ADMIN','spaces.write'),('ADMIN','audit.read'),('ADMIN','notifications.manage'),
 ('SPACE_MANAGER','spaces.read'),('SPACE_MANAGER','spaces.write'),('AUDITOR','spaces.read'),('AUDITOR','audit.read'),('MEMBER','spaces.read');
CREATE TABLE app_users (
 id uuid PRIMARY KEY, username varchar(48) UNIQUE NOT NULL, display_name varchar(64) NOT NULL,
 password_hash varchar(100) NOT NULL, department_id uuid NOT NULL REFERENCES departments(id), active boolean NOT NULL DEFAULT true,
 failed_attempts integer NOT NULL DEFAULT 0, locked_until timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE user_roles (user_id uuid REFERENCES app_users(id), role_code varchar(32) REFERENCES roles(code), PRIMARY KEY(user_id, role_code));
CREATE TABLE user_sessions (token_hash varchar(64) PRIMARY KEY, user_id uuid NOT NULL REFERENCES app_users(id), expires_at timestamptz NOT NULL);
CREATE INDEX sessions_by_user ON user_sessions(user_id);
CREATE INDEX session_expiry ON user_sessions(expires_at);

CREATE SEQUENCE resource_version;
CREATE TABLE resources (
 id uuid PRIMARY KEY, type varchar(16) NOT NULL CHECK(type IN ('CAMPUS','BUILDING','ROOM','SEAT')),
 parent_id uuid REFERENCES resources(id), code varchar(48) UNIQUE NOT NULL, name varchar(100) NOT NULL,
 department_id uuid REFERENCES departments(id), capacity integer NOT NULL CHECK(capacity BETWEEN 0 AND 10000),
 features jsonb NOT NULL DEFAULT '[]', opening_hours jsonb NOT NULL DEFAULT '[]',
 active boolean NOT NULL DEFAULT true, version bigint NOT NULL DEFAULT nextval('resource_version'), updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK ((type='CAMPUS' AND parent_id IS NULL) OR (type<>'CAMPUS' AND parent_id IS NOT NULL)),
 CHECK ((type IN ('ROOM','SEAT') AND department_id IS NOT NULL) OR (type IN ('CAMPUS','BUILDING') AND department_id IS NULL))
);
CREATE INDEX resource_parent ON resources(parent_id);
CREATE INDEX resource_department ON resources(department_id);

CREATE TABLE notification_tasks (
 id uuid PRIMARY KEY, source varchar(16) NOT NULL, event_id varchar(100) NOT NULL, recipient_id uuid NOT NULL REFERENCES app_users(id),
 payload_hash varchar(64) NOT NULL, title varchar(100) NOT NULL, body varchar(2000) NOT NULL,
 object_type varchar(48) NOT NULL, object_id varchar(128) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RETRY','DELIVERED','DEAD')),
 attempts integer NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now(), last_error varchar(64),
 created_at timestamptz NOT NULL DEFAULT now(), delivered_at timestamptz, UNIQUE(source,event_id,recipient_id)
);
CREATE INDEX notification_pending ON notification_tasks(status, next_attempt_at);
CREATE TABLE inbox (
 id uuid PRIMARY KEY REFERENCES notification_tasks(id), user_id uuid NOT NULL REFERENCES app_users(id),
 read_at timestamptz, received_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX inbox_owner ON inbox(user_id, received_at DESC);

CREATE TABLE audit_events (
 id uuid PRIMARY KEY, source varchar(16) NOT NULL, event_id varchar(100) NOT NULL,
 actor_id uuid REFERENCES app_users(id), department_id uuid REFERENCES departments(id), action varchar(64) NOT NULL,
 object_type varchar(48) NOT NULL, object_id varchar(128) NOT NULL, outcome varchar(16) NOT NULL CHECK(outcome IN ('SUCCESS','FAILURE')),
 reason_code varchar(64), request_id varchar(64) NOT NULL, payload_hash varchar(64) NOT NULL,
 occurred_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(), UNIQUE(source,event_id)
);
CREATE INDEX audit_scope ON audit_events(department_id, occurred_at DESC);
CREATE FUNCTION protect_audit_history() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'audit history is append only'; END; $$;
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON audit_events FOR EACH ROW EXECUTE FUNCTION protect_audit_history();
