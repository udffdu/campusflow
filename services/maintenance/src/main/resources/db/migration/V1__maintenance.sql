CREATE TABLE work_orders (
 id uuid PRIMARY KEY, reporter_id uuid NOT NULL, room_id uuid NOT NULL,
 department_id uuid NOT NULL, resource_version bigint NOT NULL CHECK(resource_version > 0),
 fault_type varchar(24) NOT NULL CHECK(fault_type IN ('LIGHTING','POWER','FURNITURE','HVAC','OTHER')),
 description text NOT NULL CHECK(char_length(description) BETWEEN 5 AND 1000),
 status varchar(32) NOT NULL CHECK(status = 'PENDING_DISPATCH'), created_at timestamptz NOT NULL
);
CREATE INDEX work_orders_owner ON work_orders(reporter_id,created_at DESC,id DESC);
CREATE TABLE work_order_history (
 id uuid PRIMARY KEY, work_order_id uuid NOT NULL REFERENCES work_orders(id),
 actor_id uuid NOT NULL, status varchar(32) NOT NULL, occurred_at timestamptz NOT NULL
);
CREATE TABLE creation_results (
 reporter_id uuid NOT NULL, idempotency_key varchar(128) NOT NULL,
 canonical_content text NOT NULL, work_order_id uuid REFERENCES work_orders(id), response_json text,
 PRIMARY KEY(reporter_id,idempotency_key),
 CHECK ((work_order_id IS NULL) = (response_json IS NULL))
);
CREATE TABLE audit_outbox (
 event_id uuid PRIMARY KEY, work_order_id uuid NOT NULL REFERENCES work_orders(id),
 payload text NOT NULL, created_at timestamptz NOT NULL, delivered_at timestamptz
);
