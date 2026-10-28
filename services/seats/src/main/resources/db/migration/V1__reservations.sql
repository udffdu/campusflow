CREATE TABLE m1.reservations (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    room_id uuid NOT NULL,
    seat_id uuid NOT NULL,
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('CONFIRMED','CHECKED_IN','COMPLETED','NO_SHOW','DISRUPTED')),
    source varchar(2) NOT NULL DEFAULT 'M1' CHECK (source = 'M1'),
    resource_version bigint NOT NULL CHECK (resource_version > 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK (start_at < end_at)
);
CREATE INDEX reservations_seat_time ON m1.reservations (seat_id, start_at, end_at)
    WHERE status IN ('CONFIRMED','CHECKED_IN');
CREATE INDEX reservations_user_time ON m1.reservations (user_id, start_at, end_at)
    WHERE status IN ('CONFIRMED','CHECKED_IN');

-- This table is specifically the CREATE_RESERVATION operation's namespace.
CREATE TABLE m1.reservation_requests (
    user_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    reservation_id uuid NOT NULL REFERENCES m1.reservations(id),
    PRIMARY KEY (user_id, idempotency_key)
);

-- A domain event awaiting an adapter/dispatcher, not a delivered D notification.
CREATE TABLE m1.outbox_events (
    event_id uuid PRIMARY KEY,
    reservation_id uuid NOT NULL REFERENCES m1.reservations(id),
    event_type varchar(40) NOT NULL,
    payload jsonb NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DELIVERED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at timestamptz NOT NULL
);
