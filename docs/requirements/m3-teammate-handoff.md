# M3维修同学交接：按最终Format重建

2026-11-03。任务1A/1B/1/2/3已获用户内部验收；任务4于2026-11-03获用户内部验收。这里描述当前能力，不伪造原始开发版本。真实D联调未完成。

## 共同模型与已实现边界

唯一格式依据为 [工单格式v1](../../contracts/m3-work-order-format-v1.md)，与项目上级Format.md一致。复用 services/maintenance、apps/web 和M3自有业务表，不另建工单表、认证系统或跨模块ORM。

上报 → PENDING_DISPATCH → 首次派单 → ASSIGNED 的业务链已实现，并用真实临时PostgreSQL验证。真实候选及资格仍依赖D，默认适配器返回503；因此尚不能演示真实D授权的完整链路。

| 交接内容 | 约定 |
| --- | --- |
| 工单 | id/reporterId/roomId/departmentId/faultType/description/status/assigneeId/dispatcherId/createdAt/assignedAt/updatedAt/version/history，字段解释见统一格式 |
| 已派单事实 | assigneeId、dispatcherId、assignedAt非空，version=2；真实CREATED与ASSIGNED历史各一条 |
| 历史 | id/action/actorId/fromStatus/toStatus/assigneeId/remark/occurredAt；按occurredAt、id升序，时间UTC传输、上海展示 |
| 查询裁剪 | 本人完整详情14字段；管理详情不返回reporterId/departmentId，返回受权历史中的操作者ID；列表不返回history |
| 数据库存储 | work_orders；work_order_history中的status映射toStatus，actor_id来自已验证会话；resource_version仅内部保存 |
| 事务边界 | 工单状态、版本、历史、业务幂等结果及必要outbox同一本地事务；跨服务调用不冒充本地原子提交 |

历史没有普通用户修改/删除API。当前没有数据库防篡改触发器；有数据库权限的维护者仍可修改，不宣称密码学防篡改。D集中审计独立投递，不能用它替代本地历史。

## 可复用入口

所有路径位于 /api/v1/maintenance：

| 接口 | 用途 |
| --- | --- |
| GET /csrf | M3独立令牌，写请求使用X-M3-XSRF-TOKEN |
| POST /work-orders | roomId/faultType/description，Idempotency-Key，同键同内容返回原创建快照 |
| GET /work-orders、/{id}、/{id}/history | 当前真实报修人的列表、详情、历史，total同样隔离 |
| GET /management/work-orders、/{id}、/{id}/history | maintenance.read.scope，按获权ADMIN全局/其他获权者本院系 |
| GET /management/work-orders/{id}/candidates | maintenance.dispatch，真实候选适配待D交付 |
| POST /management/work-orders/{id}/dispatch | assigneeId/expectedVersion，Idempotency-Key；提交前重新复核资格 |

以上相对路径的/{id}均接在同一行列表路径之后。公共层spaces.write不能代替业务权限。服务端验证CF_SESSION及allowed=true，不接受自报actorId/status。D故障拒绝受保护操作。

## 后续维修开发必须另行实现

- 在ASSIGNED基础实现IN_PROGRESS → PENDING_ACCEPTANCE → COMPLETED；验收驳回回IN_PROGRESS。这些状态的展示名称已预留，但数据库当前只支持待派单/已派单，后续必须新增迁移，不改V1/V2/V3。
- 复用同一模型，提交expectedVersion，条件更新防并发；成功重试不能重复加版本、历史或事件。后续各动作的幂等键范围、权限、说明字段验证需先确认。
- 维修开始、完工、验收、超时升级、评价的服务端接口和页面尚未实现；没有可直接调用的“开始维修API”。
- 按Format追加REPAIR_STARTED/COMPLETION_SUBMITTED/ACCEPTANCE_PASSED/ACCEPTANCE_REJECTED，服务端确定操作者和时间；完工说明、驳回原因必填。
- M1限制/恢复通过其公开契约，以工单id作为来源，不直接写座位或其他模块表；上报/派单均不触发M1。
- CandidateProvider、AssigneeEligibility为D适配边界。真实HTTP方法/路径、资格撤销竞态仍待评审；不能让fixture成为生产实现。
- AuditTransport/outbox可复用；worker默认关闭，失败状态/次数/下次时间可追踪；当前不发通知。通知收件人、模板、回执先确认。

## 数据库与验收

V1创建基础表；V2首次派单；V3仅升级旧幂等响应表示，保留原业务快照和键。迁移原文与保全版本一致。应用回滚不等于数据库回滚；阶段验证使用独立临时库，不对业务库降级。实际业务数据库备份需要单独授权，源码备份不能替代。

当前后端31项、前端36项通过，D使用替身；详见 [本轮历史验收包](../process/M3/m3-rebuild-history-task4-validation.md)。
D应交付什么、M3自己需做什么见 [D交接清单](m3-d-capability-requests.md)，不要求D代写维修业务。

联调前准备不同院系真实身份和有效/无效维修人员；评审入口与独立CSRF后再启用页面开关。完整演示：管理列表 → 查看工单 → 选择维修人员 → 派单 → 刷新确认 → 查看操作历史；当前仅提供待联调步骤，不宣称实际完成。
