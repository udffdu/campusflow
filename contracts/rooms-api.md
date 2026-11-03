# M2 API v1

本地实现契约，待用户验收和相关负责人评审。路由前缀 `/api/v1/room-applications`，前端与 D 同源，网关转发到 M2:8082。

## 身份、错误与范围

M2 从 HttpOnly `CF_SESSION` 读取令牌，服务端使用 `M2_SERVICE_KEY` 请求 D `/internal/v1/authorization` 的 `rooms.read`。逐请求验证会话、实时权限；D 故障返回 503，失效身份 401，禁止接受浏览器提供 actorId。写入复用 D `/auth/csrf` 的 XSRF-TOKEN Cookie + X-XSRF-TOKEN 头，D 登录/退出重置规则不变。

错误体 `{code,message,requestId}`。400 输入/时间/容量，401 会话，403 资格/范围/CSRF，404 不存在或不可读，409 版本/重复/状态冲突，503 依赖不可用。所有响应 no-store。对外请求 JSON 拒绝未知字段。可信 D 身份响应允许新增资料字段（例如 email），但身份 ID、院系、角色、权限缺失或格式错误仍拒绝；allowed 与 active 检查不变。

| 方法与相对路径 | 请求 / 行为 |
| --- | --- |
| GET `/health` | M2 数据库检查；不表示 D/M1 可用 |
| GET `/policy` | 两级 steps、approvalHours、maxHours、advanceDays、minuteStep=30、minLeadMinutes=15、timezone |
| GET `?view=mine&status=&page=0&size=20` | Page `{items,page,size,total}`；size 1～100。mine 本人、review 本人可操作节点且未过期且非自审/已审批人、manage 需 rooms.manage 范围内全部申请。 |
| POST `/` | Create → 201 Detail；相同 requestKey 相同载荷返回原记录，包括已结束的记录，不重新申请。 |
| GET `/{id}` | Detail，仅本人或有范围的审核/管理员 |
| POST `/{id}/decisions` | `{expectedVersion,step,decision:"APPROVE"或"REJECT",comment}`；comment 必填最长 1000；200 Detail |
| POST `/{id}/cancel` | `{expectedVersion,comment}`；200 Detail，可能为 RELEASING |
| POST `/{id}/retry` | `{expectedVersion,comment}`，rooms.manage + 院系；仅处理中、释放中或有 DEAD 事件时允许；不会绕过状态确认 |

Create：`{requestKey:UUID,roomId:UUID,title,purpose,attendees,startAt,endAt}`。title 1～100，purpose 1～2000，attendees 1～10000且不超过容量，时间为带时区的 ISO8601，建议 UTC `Z`。

Application 包含：`id,applicantId,applicantName,departmentId,roomId,roomName,resourceVersion,title,purpose,attendees,startAt,endAt,deadline,steps:[{name,permission}],currentStep,status,version,operationVersion,attempts,nextAttemptAt,lastError,needsAttention,createdAt,updatedAt`。

Detail：`{application,history,deliveries}`。history 每条含 `id,version,action,actorId,actorName,step,comment,occurredAt`；系统动作 actorId=null、actorName=系统自动处理。deliveries 每条含 `id,kind,status,attempts,lastError`，不含服务凭据。kind=AUDIT/NOTIFICATION，status=PENDING/SENT/DEAD。SENT 仅表示 D 接收；最终站内信可读状态仍归 D。

状态见 [M2 规则](../docs/requirements/m2.md)。前端不能仅凭 currentStep=2 显示已预约；必须看 status=APPROVED。收到 409 刷新详情，由用户重新决定；不要自动重放审批决定。提交网络失败可用原 requestKey 和完全相同内容重试。

## D 的权限扩展

新增迁移 `V2_1__room_permissions.sql`：rooms.read / apply / review / approve / manage。已有内置角色仅新增 read；ADMIN 获得所有 M2 权限。新增 ROOM_APPLICANT、ROOM_REVIEWER、ROOM_APPROVER，三者分别用于申请/初审/终审，终审员附带恢复和撤销管理权限。新角色禁止用户修改内置定义；自定义角色必须显式含 rooms.read 及需要的动作权限。

V2.1 文件及校验和保留。D 的启动迁移策略只允许 `V2_1__room_permissions.sql` 作为唯一被忽略的旧版本脚本补执行；此路径仍执行 Flyway 的完整校验，不 repair、不修改历史、不全局开启乱序迁移。出现其他旧版本脚本、已应用迁移缺失或校验和变化时仍启动失败。M1/M2 集成发布物必须包含双方完整 D 迁移集合与此策略，不能用缺少另一方迁移的单分支包替换集成服务。升级测试和限制见 [PR #4 修复验证](../docs/process/m2-pr4-review-fixes.md)。

D 通知 `eventId=historyId`，来源由服务凭据决定；M2 保存 outbox 并反复发送同一载荷。审计自动动作使用 `ROOM.SYSTEM_*`，actorId 为申请发起人、reasonCode=AUTOMATED_ON_BEHALF_OF_APPLICANT，用于兼容 D 必填 actorId；并不声称该人执行了自动动作，真实系统执行人语义保留在 M2 history。
