CREATE TABLE m1.reservation_transitions (
    transition_id uuid PRIMARY KEY,
    reservation_id uuid NOT NULL REFERENCES m1.reservations(id),
    from_status varchar(20) NOT NULL,
    to_status varchar(20) NOT NULL,
    reason varchar(40) NOT NULL,
    actor_user_id uuid,
    reservation_version bigint NOT NULL CHECK (reservation_version > 1),
    occurred_at timestamptz NOT NULL,
    CHECK (from_status IN ('CONFIRMED','CHECKED_IN','COMPLETED','NO_SHOW','DISRUPTED')),
    CHECK (to_status IN ('CONFIRMED','CHECKED_IN','COMPLETED','NO_SHOW','DISRUPTED')),
    CHECK (from_status <> to_status),
    UNIQUE (reservation_id, reservation_version)
);
CREATE INDEX reservation_transitions_timeline
    ON m1.reservation_transitions (reservation_id, occurred_at, transition_id);

-- Durable, shared throttling state. It works consistently across instances and restarts.
CREATE TABLE m1.checkin_rate_limits (
    user_id uuid NOT NULL,
    reservation_id uuid NOT NULL REFERENCES m1.reservations(id) ON DELETE CASCADE,
    window_started_at timestamptz NOT NULL,
    failures integer NOT NULL CHECK (failures BETWEEN 1 AND 5),
    blocked_until timestamptz,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, reservation_id),
    CHECK (blocked_until IS NULL OR blocked_until > window_started_at)
);

-- A lifecycle event is a fact, not a delivery attempt. Retries update the existing row.
CREATE UNIQUE INDEX outbox_reservation_event_once
    ON m1.outbox_events (reservation_id, event_type);
