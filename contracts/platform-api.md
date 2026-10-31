# D API v1

实现版，待 M1/M2/M3 负责人评审。D 不判断预约空闲，不保存申请或工单状态。

2026-10-28补充：用户授权直接新增M1预约权限、预约开放范围和座位插座标记。2026-10-30 又授权 CF-M1-008/009 所需的用户邮箱、邮件任务及内部邮件端点；2026-10-31授权CF-M1-011所需的独立运营读取权限。负责人说明见 [接入说明书](../docs/handoffs/m1-integration.md)。这些实现不表示D负责人本人已评审，也不扩大到其他未决跨模块政策。

## 共同约定

- JSON camelCase，稳定 UUID，UTC 时间使用 ISO-8601 `Z`。教室每周规则按 Asia/Shanghai 本地时间，day=1～7（周一至周日），`[start,end)`；空规则表示没有已配置开放时段。
- 分页 `{items,page,size,total}`，page 从 0 起，size 为 1～100。列表跨请求不承诺快照，不用页码作为事件同步游标。
- 错误 `{code,message,requestId}`。400 输入/未知字段、401 身份、403 权限或 CSRF、404 不存在/不属于本人、409 版本或幂等冲突、500 服务异常。可传 `X-Request-Id`（字母数字短横线，最多 64 字符）。
- 不提供硬删除。资源 type、parentId、departmentId、code 不可修改。停用记录保留，历史业务保存 ID 及当时版本。
- 父级停用动态影响子级 `effectiveActive`，子级 version 不一定变化；**version 不是有效状态的缓存失效信号**，新业务实时查询基础有效性。
- D 的基础有效性不包括维修、占用或实际空闲。M1 仍须原子控制业务资源占用。

## 浏览器 `/api/v1`

同源访问。先 GET `/auth/csrf` 读取 `{token,headerName}`；写操作同时发送该头和 Cookie。登录/退出/改密会重置 CSRF，下一次写入先重新 GET。`CF_SESSION` 为 HttpOnly，前端不可读、不另存。会话 8 小时，权限实时读取，停用/退出立即拒绝后续请求。

| 接口 | 请求或权限 |
| --- | --- |
| GET `/health` | 数据库就绪，不需登录 |
| POST `/auth/login` | `{username,password}` → Identity，需 CSRF；失败五次锁五分钟 |
| GET `/auth/me` | Identity：`{id,username,displayName,email,departmentId,active,roles,permissions}`；email 可为 null |
| POST `/auth/logout` | 退出当前会话 |
| POST `/auth/password` | `{currentPassword,newPassword}`，成功注销本人所有会话 |
| GET `/departments` | 已登录可读 |
| POST `/departments`、PUT `/departments/{id}` | `{code,name}`，管理员；编号不可改 |
| GET `/roles`、GET `/permissions` | 管理员查询角色和权限目录 |
| POST `/roles`、PUT `/roles/{code}` | `{code,name,permissions:[]}`，管理员；内置角色受保护，自定义角色不能获得全局管理权限 |
| GET `/users` | 管理员分页查询，不返回密码哈希 |
| POST `/users` | `{username,displayName,email?,departmentId,roles:[],password}`，管理员；email 规范化为小写且全局唯一 |
| PUT `/users/{id}` | `{displayName,email?,departmentId,roles:[],active}`，管理员；禁止自降权/自停用，至少留一位有效管理员 |
| GET `/resources?type=&parentId=&q=&page=&size=`、GET `/resources/{id}` | spaces.read，包含停用记录用于历史展示 |
| POST `/resources` | ResourceCreate，spaces.write + 院系范围；校区/楼栋仅系统管理员 |
| PUT `/resources/{id}` | `{name,capacity,features,openingHours,active,expectedVersion,bookingScope?}`，冲突 409 后刷新 |
| GET `/notifications`、GET `/notifications/unread` | 本人分页站内信、`{count}` 未读数 |
| POST `/notifications/{id}/read`、POST `/notifications/read-all` | 仅本人，幂等已读 |
| GET `/deliveries?status=` | 管理员查询投递任务 |
| POST `/deliveries` | NotificationEvent，管理员发送，来源固定 D；202 只代表入队 |
| POST `/deliveries/{id}/retry` | 管理员重试 RETRY/DEAD |
| GET `/audit?source=&outcome=` | audit.read；管理员全局，其他角色仅本院系 |

ResourceCreate：`{type,parentId,code,name,departmentId,capacity,features:[],openingHours:[{day,start,end}],active,bookingScope?}`。
层级 CAMPUS→BUILDING→ROOM→SEAT；教室/座位需要院系；座位与父教室院系一致、容量为 1；教室容量至少 1。响应额外包括 `id,effectiveActive,version,updatedAt`。
每教室最多 28 个周时段，必须按分钟、同日不重叠；跨夜拆成两条。资源跨院系迁移或重挂载暂不支持，需定义历史业务处理后再扩展。

角色 ADMIN 全局；SPACE_MANAGER 维护本院系空间；AUDITOR 查询本院系审计；MEMBER 查看目录与本人通知。自定义角色同样以用户所在院系限定数据范围。

### M1 补充字段与权限（2026-10-28）

- ROOM的bookingScope为DEPARTMENT（本院系可约）或CAMPUS（全校可约），与管理归属departmentId分开。新建ROOM省略/null时默认DEPARTMENT；更新省略/null时保留现值。其他资源类型不能设置非空bookingScope，响应为null；座位使用所属教室范围。
- 修改范围仍需spaces.write和原有管理院系范围，使用expectedVersion并递增教室version；向全校开放不赋予外院系管理权限。旧库ROOM通过V3迁移回填DEPARTMENT并更新版本；兼容旧二进制新写入的ROOM.booking_scope=NULL，新D读取时解释为DEPARTMENT，不解释为全校开放。
- SEAT.features中的精确标记POWER_OUTLET表示已确认有插座。非SEAT不接受该保留标记；其他自由文本仍可保留。教室属性和“电源”等历史文本不自动转换或继承，未标记座位不列入“有插座”筛选。
- 新增权限seats.availability.read、seats.reservations.create、seats.reservations.read.own，分别用于查询、创建本人预约和本人历史。均可授予自定义角色；新增受保护的内置SEAT_USER角色含三项权限。没有为任何已有账号或ADMIN/MEMBER自动赋权，管理员应在用户既有角色上显式附加SEAT_USER或适当自定义角色。
- 当前M1内部规则要求权限与预约范围同时通过，个人预约对ADMIN不设院系豁免；CAMPUS允许有业务权限的跨院系用户。read-own不等于读取他人历史或B的运营查询权限。D既有authorization的管理员管理范围例外保持原样，调用方仍需执行M1业务范围判断。
- V5登记`seats.operations.read`供CF-M1-011只读运营查询使用。该权限可授予自定义角色，但不自动加入ADMIN、MEMBER、SEAT_USER或任何已有用户；D只返回当前身份和院系，M1仍必须限制资源范围且不得据权限返回学生身份。

这些字段/权限已实现于本分支，不表示全组评审、远程CI或部署完成。V1/V2保持不变；预约元数据使用V3，邮件扩展使用V4，运营权限使用`V5__seat_operations_permission.sql`。

## 内部 `/internal/v1`

每个模块独立至少 32 字符随机 Bearer 密钥：`M1_SERVICE_KEY`、`M2_SERVICE_KEY`、`M3_SERVICE_KEY`。空值禁用。来源从凭据推导，不接受载荷的 source。浏览器会话不能替代服务凭据。仅服务专网开放；正式入口配合 TLS。禁止记录 Cookie、密码或授权请求体。

| 接口 | 约定 |
| --- | --- |
| POST `/authorization` | `{sessionToken,permission,departmentId?}` → `{allowed,identity?}`；失效会话返回 allowed=false |
| GET `/resources?type=&parentId=&page=&size=`、GET `/resources/{id}` | 主数据，含基础有效性/版本，不含预约和维修事实 |
| POST `/notifications` | NotificationEvent → Delivery，202；按 source+eventId+recipientId 去重，载荷不同 409 |
| GET `/notifications/{id}` | 仅本服务来源的回执，其他来源 404 |
| POST `/email-notifications` | NotificationEvent → Delivery，202；独立邮件任务，按 source+eventId+recipientId 去重，载荷不同 409 |
| GET `/email-notifications/{id}` | 仅本服务来源的邮件回执，其他来源 404 |
| POST `/audit` | AuditEvent → `{id}`；按 source+eventId 去重，载荷不同 409 |

业务服务从浏览器转发的 HttpOnly CF_SESSION Cookie 提取 sessionToken，在服务端调用 authorization；不要求前端读取会话。业务服务不能信任用户自报 actorId。D 不可用时，受保护写入应拒绝。
当前permission目录包含D原有权限、三项个人座位权限及`seats.operations.read`；permission参数仍仅接受`[a-z.]{1,64}`。不要把spaces.write当作预约、运营、审批或派单资格。M2/M3其他业务权限尚未登记，仍需对应方案。

NotificationEvent（虚构示例）：

```json
{
  "eventId": "room-42:approved:v1",
  "recipientId": "00000000-0000-0000-0000-000000000042",
  "title": "教室申请已通过",
  "body": "请前往申请记录查看结果。",
  "objectType": "ROOM_APPLICATION",
  "objectId": "42"
}
```

Delivery 包含 `id,source,eventId,recipientId,title,status,attempts,nextAttemptAt,lastError,createdAt`。
站内信状态为 PENDING→DELIVERED；收件人停用则 RETRY，指数退避，五次后 DEAD，管理员可重试。数据库故障则批次回滚，任务留存，下轮恢复。站内信写入和 DELIVERED 同事务；SKIP LOCKED 支持多个工作进程。它是真实持久化通道，但不保证用户已读。

邮件任务独立保存于 V4 的 `email_notification_tasks`，状态为 PENDING/DELIVERING/RETRY/DELIVERED/DEAD。工作进程以租约认领；收件人停用、缺少邮箱、邮件通道关闭或 SMTP 失败均保留安全错误码并退避，最多五次尝试。`PLATFORM_MAIL_ENABLED` 默认为 false；正式启用时从环境变量读取 SMTP、发件人、STARTTLS/SSL 和超时配置。发送使用稳定任务 ID 生成稳定 `Message-ID`，但 SMTP 在不确定响应下只能提供至少一次尝试语义，不能承诺严格 exactly-once。当前真实外部服务商尚未验收。

AuditEvent：`{eventId,actorId,departmentId,action,objectType,objectId,outcome,reasonCode?,occurredAt}`。
action 为大写字母数字、点和下划线；outcome=SUCCESS/FAILURE；reasonCode 只能为大写枚举式理由码；未知字段拒绝。事件时间不得晚于当前一分钟。
D 记录已认证服务关于 actor/object 的声明，业务服务负责验证用户及业务事实、对象与院系归属。D 不独立确认业务操作是否真的发生。触发器拒绝 update/delete，数据库管理员仍能维护，所以不声称为密码学防篡改。

## M1/M2/M3 接入前必须约定

1. 三项个人座位权限以外的角色映射、调用超时、密钥发放和轮换、其他业务院系边界与 D 备用负责人。
2. M1 查询资源后原子占用；查询和占用之间可能被管理员停用，需要双方确定事务与补偿策略。D 不提供分布式预约锁。
3. M2/M3 在业务事务中写自己的 outbox，重试时保留 eventId；通知/审计故障不撤销已完成的业务结果。D 无法保证尚未收到的事件不丢失。
4. 维修、整教室占用、个人预约及解除的来源隔离仍由 M1/M2/M3 共同实现。
5. 资源变更生效、审计保留期限、HTTPS、备份恢复与云端全组部署须实际评审验证。本文不是全组一致记录。
