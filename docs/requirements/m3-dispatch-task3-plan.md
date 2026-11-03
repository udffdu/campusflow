# 任务3首次派单：待评审实施方案

2026-11-02。用户本轮明确回复：尚未评审，先准备方案和不依赖迁移的部分。任务3未完成，未获schema评审，不创建自动执行迁移、不启用派单接口/提交按钮。任务1/2的真实D联调结果未因此改变。

## 不依赖迁移的已做部分

新增 AssigneeEligibility（资格复核适配口）、PendingAssigneeEligibility（未接入拒绝503）、EligibilityGuard（要求明确allowed=true且人员/房间/院系与请求一致）、EligibilityGuardTest。与候选列表口分开，不将“出现在候选中”当作提交时资格。当前没有派单写接口调用guard，因此测试不能证明派单已实现。

D适配请求 actorId 来自已验证会话；assigneeId来自提交，roomId/departmentId从M3工单读取。D需要验证启用、维修资格、服务范围，返回 assigneeId/roomId/departmentId/allowed/reasonCode。HTTP地址、凭据和资格变更竞态尚待D评审；不会调用虚构接口。reasonCode不原样展示，避免泄漏内部数据。

## 新迁移提案（未实施）

拟新增 V2__dispatch.sql，保留V1原文和校验和：

| 表 | 变更 |
| --- | --- |
| work_orders | assignee_id uuid、dispatcher_id uuid、assigned_at timestamptz可空；updated_at回填created_at后NOT NULL；version bigint默认1且>0；状态约束扩展到PENDING_DISPATCH/ASSIGNED；约束待派单的派单字段为空、已派单三字段非空 |
| work_order_history | action、from_status、assignee_id、remark；现有记录回填CREATED，status作为兼容的toStatus存储，创建fromStatus为空；派单必须ASSIGNED且前状态PENDING_DISPATCH |
| dispatch_results | actor_id + idempotency_key唯一；canonical_content包含workOrderId/assigneeId/expectedVersion；保存完整response_json及work_order_id，事务内建立与完成 |
| audit_outbox | attempts、next_attempt_at、last_error_code、status；已有delivered_at有值回填DELIVERED，否则PENDING；失败理由仅枚举码，不存凭据或完整异常 |

兼容要求：所有现有无列名INSERT改为显式列名；新建工单updatedAt=createdAt、version=1。管理/本人详情返回实际派单字段；任务1状态筛选支持ASSIGNED，不再固定assigneeId=null。历史详情响应逐步对齐统一工单v1格式，保留旧status字段兼容；完整历史页面仍属任务4。

## 派单接口与事务提案

POST /api/v1/maintenance/management/work-orders/{id}/dispatch
头：Idempotency-Key、M3独立CSRF。JSON仅assigneeId、expectedVersion；未知或受控字段拒绝。

1. D验证会话和maintenance.dispatch allowed=true；工单范围取服务端身份与存储的院系。
2. 先校验输入及权限范围。事务中占用用户/派单动作范围的幂等键；同键不同内容409，同键成功结果重放不重新修改工单。
3. 新操作校验待派单状态/version，并调用资格复核。缺失能力/网络故障拒绝，资格不合格409。网络检查限时，不能保证跨D/M3的原子资格快照，最终竞态规则需D评审。
4. 用 WHERE id=? AND status='PENDING_DISPATCH' AND version=? 条件更新，受影响行必须为1；否则409并回滚。成功设ASSIGNED、人员/操作者/时间、version+1。
5. 同事务追加恰好一条派单历史，写稳定eventId审计outbox及幂等响应。任一失败全部回滚；不调用M1。
6. 响应包含新的工单版本和真实派单字段；同键重放返回原响应。权限撤销后不允许以幂等重放绕过当前权限。

前端拟在已有候选组件外增加独立派单面板；冻结人员/expectedVersion/幂等键后发送，连点只一次。网络结果不明保留原请求重试；409提示刷新，资格变化重新选择，禁止静默换人或换键重试。成功刷新详情，显示ASSIGNED。刷新页面的未确认请求恢复另行明确，不能假称内存状态可持久化。

## 审计发送和恢复最小方案（待评审）

仅派单审计，暂不生成收件人未确定的通知。action建议MAINTENANCE.ASSIGNED；objectType=WORK_ORDER，不包含故障全文。复用D /internal/v1/audit和source+eventId去重。

worker仅处理已提交outbox，稳定eventId不变；成功记录delivered_at，失败记录枚举原因码、attempts及下次重试时间。建议指数退避并设置上限，无静默丢弃；认证/载荷错误进入待人工处理状态，通过受控恢复动作重新入队，具体操作权限和上限一起评审。请求超时可能D已接收，重发依靠D去重；不把外部发送放进派单业务事务。运行状态及恢复入口需有测试和人工步骤，不能仅写表就宣称可靠投递完成。

## 任务3必须执行的验证（尚未执行）

真实PG：合法派单与刷新、无权限/越范围/无资格/非法状态、两管理员并发只有一次成功、同键并发只一份结果、不同内容冲突、历史/outbox/响应阶段注入失败全部回滚、V1升级到V2及空库重复迁移。
D替身：资格变化、拒绝/缺失/畸形响应、审计超时/重复接收/失败恢复；真实D结果另列。
页面：连续点击、重试原键、状态冲突、人员变化、成功刷新及会话失效。
必须临时破坏派单并发条件或权限判断，确认行为测试失败，恢复后完整通过。当前无派单实现，不能用资格guard单测冒充该变异验收。

## 评审确认栏

M3同伴/schema负责人、确认日期、方案调整：待填。
D资格复核契约及竞态、审计语义：待评审。
通知：本轮建议暂不发送，待确认。
获得确认后才新增迁移并继续派单事务/页面/投递恢复实现，最后提交任务3独立验收包，不进入任务4。

## 本轮实际检查记录

执行：现有Maven -o -Dmaven.repo.local=D:/CampusFlow-Tools/m2/repository -Dtest=EligibilityGuardTest -f services/maintenance/pom.xml test（JAVA_HOME=D:/oracleJdk-27）。3 tests，0 failures/errors/skipped，BUILD SUCCESS。日志 services/maintenance/target/eligibility-test.log。
测试仅验证明确allowed、上下文匹配和默认拒绝，不涉及真实数据库或D网络，不代表派单验收通过。没有运行/更改迁移，没有新增页面或重跑前端构建。
建议准备阶段提交说明：feat(m3): [CF-M3-003] 预留派单资格复核并记录待评审方案。未实际提交或推送。

2026-11-03更新：用户明确要求继续完成任务3并给D留接口，内部方案已实施；见 ../process/m3-dispatch-task3-validation.md。本文此前准备状态为历史记录，外部评审仍待完成。
