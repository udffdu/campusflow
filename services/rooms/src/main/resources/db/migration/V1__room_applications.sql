CREATE TABLE m2.applications (
 id uuid PRIMARY KEY,
 applicant_id uuid NOT NULL,
 applicant_name varchar(64) NOT NULL,
 department_id uuid NOT NULL,
 room_id uuid NOT NULL,
 room_name varchar(100) NOT NULL,
 resource_version bigint NOT NULL CHECK(resource_version > 0),
 title varchar(100) NOT NULL,
 purpose varchar(2000) NOT NULL,
 attendees integer NOT NULL CHECK(attendees > 0),
 start_at timestamptz NOT NULL,
 end_at timestamptz NOT NULL,
 deadline timestamptz NOT NULL,
 steps jsonb NOT NULL,
 current_step integer NOT NULL DEFAULT 0,
 status varchar(24) NOT NULL CHECK(status IN ('PENDING','PROCESSING','APPROVED','REJECTED','EXPIRED','CANCELLED','CONFLICT','RELEASING')),
 version bigint NOT NULL DEFAULT 1,
 operation_version bigint NOT NULL DEFAULT 0,
 release_result varchar(24),
 attempts integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 last_error varchar(64),
 needs_attention boolean NOT NULL DEFAULT false,
 request_key uuid NOT NULL,
 request_hash char(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(applicant_id,request_key),
 CHECK(start_at < end_at AND deadline <= start_at)
);
CREATE INDEX applications_applicant ON m2.applications(applicant_id, created_at DESC);
CREATE INDEX applications_department ON m2.applications(department_id, status, created_at DESC);
CREATE INDEX applications_due ON m2.applications(next_attempt_at) WHERE status IN ('PROCESSING','RELEASING');
CREATE TABLE m2.application_history (
 id uuid PRIMARY KEY,
 application_id uuid NOT NULL REFERENCES m2.applications(id),
 version bigint NOT NULL,
 action varchar(40) NOT NULL,
 actor_id uuid,
 actor_name varchar(64) NOT NULL,
 step integer,
 comment varchar(1000) NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(application_id,version)
);
CREATE TABLE m2.outbox (
 id uuid PRIMARY KEY,
 application_id uuid NOT NULL REFERENCES m2.applications(id),
 kind varchar(16) NOT NULL CHECK(kind IN ('AUDIT','NOTIFICATION')),
 payload jsonb NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SENT','DEAD')),
 attempts integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz NOT NULL DEFAULT now(),
 last_error varchar(64),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX outbox_due ON m2.outbox(next_attempt_at) WHERE status='PENDING';
CREATE FUNCTION m2.immutable_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Application history is append-only'; END; $$;
CREATE TRIGGER history_immutable BEFORE UPDATE OR DELETE ON m2.application_history
 FOR EACH ROW EXECUTE FUNCTION m2.immutable_history();
