ALTER TABLE work_orders DROP CONSTRAINT work_orders_status_check;
ALTER TABLE work_orders ADD COLUMN assignee_id uuid, ADD COLUMN dispatcher_id uuid, ADD COLUMN assigned_at timestamptz,
 ADD COLUMN updated_at timestamptz, ADD COLUMN version bigint NOT NULL DEFAULT 1 CHECK(version>0);
UPDATE work_orders SET updated_at=created_at;
ALTER TABLE work_orders ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE work_orders ADD CHECK(status IN ('PENDING_DISPATCH','ASSIGNED')),
 ADD CHECK((status='PENDING_DISPATCH' AND assignee_id IS NULL AND dispatcher_id IS NULL AND assigned_at IS NULL) OR (status='ASSIGNED' AND assignee_id IS NOT NULL AND dispatcher_id IS NOT NULL AND assigned_at IS NOT NULL));
ALTER TABLE work_order_history ADD COLUMN action varchar(32) NOT NULL DEFAULT 'CREATED', ADD COLUMN from_status varchar(32), ADD COLUMN assignee_id uuid, ADD COLUMN remark text;
CREATE TABLE dispatch_results(actor_id uuid NOT NULL,idempotency_key varchar(128) NOT NULL,canonical_content text NOT NULL,response_json text,PRIMARY KEY(actor_id,idempotency_key));
ALTER TABLE audit_outbox ADD COLUMN attempts integer NOT NULL DEFAULT 0, ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN last_error_code varchar(64), ADD COLUMN status varchar(16) NOT NULL DEFAULT 'PENDING';
UPDATE audit_outbox SET status='DELIVERED' WHERE delivered_at IS NOT NULL;
ALTER TABLE audit_outbox ADD CHECK(status IN ('PENDING','RETRY','DELIVERED'));
