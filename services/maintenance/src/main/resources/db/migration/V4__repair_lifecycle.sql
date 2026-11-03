-- Proposed M3-B extension: review with M3-A before applying to shared databases.
ALTER TABLE work_orders DROP CONSTRAINT work_orders_status_check;
ALTER TABLE work_orders DROP CONSTRAINT work_orders_check;
ALTER TABLE work_orders ADD CONSTRAINT work_orders_lifecycle_status CHECK
 (status IN ('PENDING_DISPATCH','ASSIGNED','IN_PROGRESS','PENDING_ACCEPTANCE','COMPLETED'));
ALTER TABLE work_orders ADD CONSTRAINT work_orders_dispatch_facts CHECK
 ((status='PENDING_DISPATCH' AND assignee_id IS NULL AND dispatcher_id IS NULL AND assigned_at IS NULL)
 OR (status<>'PENDING_DISPATCH' AND assignee_id IS NOT NULL AND dispatcher_id IS NOT NULL AND assigned_at IS NOT NULL));
CREATE INDEX work_orders_assignee ON work_orders(assignee_id,created_at DESC,id DESC);
CREATE TABLE lifecycle_results (
 actor_id uuid NOT NULL, action varchar(32) NOT NULL, work_order_id uuid NOT NULL REFERENCES work_orders(id),
 idempotency_key varchar(128) NOT NULL, canonical_content text NOT NULL, response_json text,
 PRIMARY KEY(actor_id,action,work_order_id,idempotency_key)
);
CREATE TABLE maintenance_intents (
 work_order_id uuid NOT NULL REFERENCES work_orders(id), operation_version bigint NOT NULL CHECK(operation_version>0),
 room_id uuid NOT NULL, target varchar(16) NOT NULL CHECK(target IN ('BLOCKED','RELEASED')),
 created_at timestamptz NOT NULL, sync_status varchar(16) NOT NULL DEFAULT 'UNCONFIRMED' CHECK(sync_status IN ('UNCONFIRMED','CONFIRMED')),
 PRIMARY KEY(work_order_id,operation_version)
);
