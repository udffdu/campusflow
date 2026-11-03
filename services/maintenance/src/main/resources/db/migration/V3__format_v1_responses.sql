-- Preserve business snapshots and idempotency keys. Only upgrade the response representation.
CREATE FUNCTION m3_format_history(order_id uuid, cutoff timestamptz, creation_only boolean)
RETURNS jsonb LANGUAGE sql STABLE AS $$
 SELECT coalesce(jsonb_agg(jsonb_build_object(
 'id',id,'action',action,'actorId',actor_id,'fromStatus',from_status,'toStatus',status,
 'assigneeId',assignee_id,'remark',remark,
 'occurredAt',to_char(occurred_at AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"')) ORDER BY occurred_at,id),'[]'::jsonb)
 FROM work_order_history WHERE work_order_id=order_id AND (creation_only OR occurred_at<=cutoff) AND (NOT creation_only OR action='CREATED')
$$;
UPDATE creation_results SET response_json=(
 (response_json::jsonb - 'resourceVersion' - 'history') || jsonb_build_object(
 'assigneeId',null,'dispatcherId',null,'assignedAt',null,'updatedAt',response_json::jsonb->'createdAt','version',1,
 'history',m3_format_history(work_order_id,(response_json::jsonb->>'createdAt')::timestamptz,true)))::text
WHERE response_json IS NOT NULL;
UPDATE dispatch_results SET response_json=(
 (response_json::jsonb - 'resourceVersion' - 'history') || jsonb_build_object('history',
 m3_format_history((response_json::jsonb->>'id')::uuid,(response_json::jsonb->>'assignedAt')::timestamptz,false)))::text
WHERE response_json IS NOT NULL;
DROP FUNCTION m3_format_history(uuid,timestamptz,boolean);
