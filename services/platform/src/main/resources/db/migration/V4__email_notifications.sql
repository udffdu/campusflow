ALTER TABLE app_users ADD COLUMN email varchar(254);
CREATE UNIQUE INDEX app_users_email_unique ON app_users (lower(email)) WHERE email IS NOT NULL;
ALTER TABLE app_users ADD CONSTRAINT app_users_email_not_blank
    CHECK (email IS NULL OR (email = lower(email) AND email = btrim(email) AND length(email) BETWEEN 3 AND 254));

CREATE TABLE email_notification_tasks (
    id uuid PRIMARY KEY,
    source varchar(16) NOT NULL,
    event_id varchar(100) NOT NULL,
    recipient_id uuid NOT NULL REFERENCES app_users(id),
    payload_hash varchar(64) NOT NULL,
    title varchar(100) NOT NULL,
    body varchar(2000) NOT NULL,
    object_type varchar(48) NOT NULL,
    object_id varchar(128) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','DELIVERING','RETRY','DELIVERED','DEAD')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_until timestamptz,
    last_error varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    delivered_at timestamptz,
    UNIQUE (source,event_id,recipient_id),
    CHECK ((status='DELIVERING') = (lease_until IS NOT NULL)),
    CHECK (delivered_at IS NULL OR status='DELIVERED')
);
CREATE INDEX email_notification_due
    ON email_notification_tasks (next_attempt_at,created_at)
    WHERE status IN ('PENDING','RETRY','DELIVERING');
