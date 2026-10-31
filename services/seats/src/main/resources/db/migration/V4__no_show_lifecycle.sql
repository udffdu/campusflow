CREATE TABLE m1.reservation_violations (
    violation_id uuid PRIMARY KEY,
    reservation_id uuid NOT NULL UNIQUE REFERENCES m1.reservations(id),
    user_id uuid NOT NULL,
    room_id uuid NOT NULL,
    seat_id uuid NOT NULL,
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    reason varchar(32) NOT NULL CHECK (reason='NO_SHOW'),
    recorded_at timestamptz NOT NULL,
    reservation_version bigint NOT NULL CHECK (reservation_version > 1),
    CHECK (start_at < end_at)
);
CREATE INDEX reservation_violations_user_time
    ON m1.reservation_violations (user_id,recorded_at DESC,violation_id);

CREATE INDEX reservations_no_show_due
    ON m1.reservations (start_at,id) WHERE status='CONFIRMED';
CREATE INDEX reservations_completion_due
    ON m1.reservations (end_at,id) WHERE status='CHECKED_IN';

ALTER TABLE m1.outbox_events DROP CONSTRAINT outbox_events_status_check;
ALTER TABLE m1.outbox_events ADD COLUMN superseded_at timestamptz;
ALTER TABLE m1.outbox_events
    ADD CONSTRAINT outbox_events_status_check
        CHECK (status IN ('PENDING','DELIVERING','DELIVERED','SUPERSEDED')),
    ADD CONSTRAINT outbox_superseded_shape
        CHECK ((status='SUPERSEDED') = (superseded_at IS NOT NULL));

DROP INDEX m1.outbox_reminder_due;
CREATE INDEX outbox_lifecycle_notification_due ON m1.outbox_events (next_attempt_at,created_at)
    WHERE event_type IN ('RESERVATION_START_REMINDER','RESERVATION_LATE_REMINDER','RESERVATION_NO_SHOW')
      AND status IN ('PENDING','DELIVERING');
