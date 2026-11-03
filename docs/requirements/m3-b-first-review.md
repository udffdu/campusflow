# B 第一次 Review：维修与验收最小迭代

2026-11-03，用户授权开始开发。基于 A 8a5fdb2，无冲突 fast-forward 接入；不是合入 main。

范围：CF-M3-004 本人待修查询/开始维修/完工；CF-M3-005 报修人验收通过/驳回。完成条件：真实临时 PostgreSQL、完整上报派单交接、幂等/权限/并发/回滚测试；页面有清晰可见状态和失败反馈。超时升级 CF-M3-006、评价 CF-M3-007 保持 planned，规则待确认。

方案：扩展现有 JDBC 服务，不新建工单表。V4 同时扩展两条工单约束，新增动作幂等记录和 M1 目标事件；V1/V2/V3 不重写。V4 为双方 schema 评审提案，不运行共享/业务库。沿用 Idempotency-Key，不再引入第二套请求标识。

首轮内部规则：当前 assignee 且 maintenance.repair 获权者查询自己的任务并开始/完工；报修人且 maintenance.accept 获权者验收，不允许维修人验收自己的工单。管理员代验本轮不实现。完工说明和驳回原因 strip 后 1–1000 Unicode 码点，验收通过说明可空。拒绝未知字段。状态按 Format；升级/评价后续独立设计。

候选取舍：使用统一动作命令和目标状态映射，避免四个接口复制事务；动作由路由枚举确定，客户端不能传任意目标状态。响应保存业务快照，同身份/动作/工单/键同内容重放，不再次写历史/事件；当前权限撤销仍拒绝。expectedVersion 对新请求实行条件更新。所有成功结果、历史、审计及 M1 意图同事务。

M1 还未交付：开始维修保存 BLOCKED 目标，验收通过保存 RELEASED 目标，source=workOrderId、operationVersion=成功业务版本；完工/驳回不解除。队列不是下架回执，不实现虚构网络调用。响应 maintenanceSync=UNCONFIRMED，完成不表示座位恢复。API maintenance.lifecycle-enabled 默认 false，前端原 M3 开关继续关闭，不上线代理。

API /api/v1/maintenance/repair/work-orders 和 /{id}：maintenance.repair 且 assignee_id 为本人；列表含历史之外的完整格式。POST /repair/work-orders/{id}/start 或 /completion：expectedVersion/remark。POST /work-orders/{id}/acceptance/{pass|reject}：maintenance.accept 且 reporter_id 为本人，拒绝职责冲突。JSON 写请求均使用 M3 CSRF 和 Idempotency-Key。

外部待审：D 的 repair/accept 权限映射、M1 写回协议与已有预约免违约；与 A 确认 V4 编号和新增文件。风险：异步下架窗口、安全职责冲突、响应丢失重试；通过默认关入口、对象级鉴权、事务 outbox/幂等及真实行为测试控制。不将替身验证写作真实联调。
