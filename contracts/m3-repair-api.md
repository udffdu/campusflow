# M3-B 维修与验收 API（内部实现，外部待评审）

2026-11-03；以 Format v1 为工单事实。基于 A 8a5fdb2，未修改上报/派单请求。D 权限、M1 契约及 V4 schema 尚待受影响方评审，默认 maintenance.lifecycle-enabled=false 时所有本页接口返回 503/LIFECYCLE_NOT_ENABLED。

## 身份、权限和查询

CF_SESSION 由服务端交 D authorization 校验，必须 allowed=true。GET /api/v1/maintenance/repair/work-orders?page=0&size=20 和 GET /api/v1/maintenance/repair/work-orders/{id} 要求 maintenance.repair，只查询 assignee_id=当前身份；ADMIN 不绕过本人过滤。列表 items/page/size/total、page 0–100000、size 1–100，createdAt/id 倒序；详情有历史，列表无历史。未知/重复查询参数拒绝。非本人详情 404。

工单字段遵循 Format；授权被派人可见本工单内部事实，附 maintenanceSync。当前只有 NOT_REQUESTED（ASSIGNED）和 UNCONFIRMED（维修后阶段），没有真实 M1 回执，不出现虚构确认状态。报修人现有本人详情仍返回原 Format，页面对维修后阶段同样展示未确认提示。

## 写入

统一前缀 /api/v1/maintenance；POST 均需要独立 M3 CSRF、Idempotency-Key，载荷为 {expectedVersion: 正整数, remark?: 字符串}。未知字段拒绝，不接受客户端操作者/状态/房间。版本比较的是工单业务版本。

| 路径 | 权限与对象限制 | 前后状态 | 说明 |
| --- | --- | --- | --- |
| /repair/work-orders/{id}/start | maintenance.repair + 当前被派人 | ASSIGNED → IN_PROGRESS | remark 可空 |
| /repair/work-orders/{id}/completion | 同上 | IN_PROGRESS → PENDING_ACCEPTANCE | 完工说明必填 |
| /work-orders/{id}/acceptance/pass | maintenance.accept + 报修人，且非该工单维修人 | PENDING_ACCEPTANCE → COMPLETED | remark 可空 |
| /work-orders/{id}/acceptance/reject | 同上 | PENDING_ACCEPTANCE → IN_PROGRESS | 驳回原因必填 |

remark 使用 Java strip 去首尾空白，最多 1000 Unicode 码点，必填动作最少 1；纯文本。成功 200 返回当前业务快照含历史、maintenanceSync。增加版本、设置服务端 updatedAt、追加真实操作者/时间及派单人快照的历史。

幂等键遵循 A 的 ASCII 字母数字与 ._:-，1–128 字符；作用域为 actor+action+workOrderId+key，规范化内容含 expectedVersion 和 strip 后 remark。同键同内容返回原成功快照，即使后来版本变化也不重复业务写入；同键异内容 409。新键的旧版本/非法状态 409。重放仍先验证当前权限、对象归属和职责冲突；本轮不清理记录。

工单更新、历史、审计 outbox、动作幂等结果和必要 M1 意图同一事务。START 追加 BLOCKED，PASS 追加 RELEASED，来源为 workOrderId，操作版本为成功后的工单版本；COMPLETE/REJECT 不解除限制。maintenance_intents 仅是提案格式，未实现发送/回执；不能声称符合尚未批准的 M1 HTTP 载荷。

401 无有效身份；403 无业务权限/CSRF失败/自验收；404 非本人对象；409 状态/版本/幂等冲突；400 输入/说明错误；503 入口关闭或 D 不可用。失败不追加成功历史或事件。保留既有 code/message/requestId 错误体。

## 外部评审与后续

D 确认 maintenance.repair 和 maintenance.accept 角色映射；A 共同评审 V4 和查询/界面扩展；M1 共同确定来源版本、即时下架窗口、重试查询及免违约。管理员代验、改派、取消、超时和评价不隐式加入本轮。
