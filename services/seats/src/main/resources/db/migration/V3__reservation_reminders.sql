ALTER TABLE m1.outbox_events DROP CONSTRAINT outbox_events_status_check;
ALTER TABLE m1.outbox_events
    ADD COLUMN next_attempt_at timestamptz,
    ADD COLUMN claimed_until timestamptz,
    ADD COLUMN delivered_at timestamptz,
    ADD COLUMN last_error varchar(64);
UPDATE m1.outbox_events SET next_attempt_at=created_at;
ALTER TABLE m1.outbox_events ALTER COLUMN next_attempt_at SET NOT NULL;
ALTER TABLE m1.outbox_events ALTER COLUMN next_attempt_at SET DEFAULT now();
ALTER TABLE m1.outbox_events
    ADD CONSTRAINT outbox_events_status_check CHECK (status IN ('PENDING','DELIVERING','DELIVERED')),
    ADD CONSTRAINT outbox_claim_shape CHECK ((status='DELIVERING') = (claimed_until IS NOT NULL)),
    ADD CONSTRAINT outbox_delivery_shape CHECK (delivered_at IS NULL OR status='DELIVERED');
CREATE INDEX outbox_reminder_due ON m1.outbox_events (next_attempt_at,created_at)
    WHERE event_type IN ('RESERVATION_START_REMINDER','RESERVATION_LATE_REMINDER')
      AND status IN ('PENDING','DELIVERING');
CREATE INDEX reservations_reminder_scan ON m1.reservations (start_at,id) WHERE status='CONFIRMED';
