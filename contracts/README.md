# 跨模块接口清单（待组内评审）

M1当前已实现[查询](m1-query-api.md)、[普通个人预约/配置](m1-personal-api.md)、[本人历史/再次预约](m1-history-api.md)、[签到生命周期](m1-check-in-api.md)及[CF-M1-008/009提醒](m1-reminder-api.md)。正常jar可显式启用受保护个人写入和后台提醒，默认均关闭；普通预约窗口按用户确认的上海自然日。2026-10-30 用户决定当前签到体验暂不限制签到时间，后续与未签到自动取消的最终边界仍须在 CF-M1-010 实现前重新评审。旧[隔离演示协议](m1-reservation-demo-api.md)保留供追溯，不表示普通入口仍依赖测试装配。

M2/M3内部来源接口仍是讨论稿，未实现；D已实现请求与响应以[D API v1](platform-api.md)为准，其他负责人的评审不能从文档存在推定。每模块拥有自己的契约，调用方共同评审变更，禁止共享数据库实体作为接口。总体交付/延期项见[M1-A索引](../docs/handoffs/m1-a-delivery.md)。

| 提供方 | 接口草案 | 调用方 | 验收重点 |
| --- | --- | --- | --- |
| D | `GET /api/v1/auth/me` | web | 当前用户、权限和数据范围，不能伪造身份 |
| D | `POST /internal/v1/authorization` | M1查询/后续敏感动作 | 动作与具体资源范围校验 |
| D | `GET /api/v1/resources?type=ROOM` | web | 基础筛选；不声称此刻可预约 |
| D | `GET /internal/v1/resources/{id}` | M1/M2/M3 | 开放规则、归属、启停、版本 |
| D | `GET /internal/v1/resources?type=SEAT&parentId={id}` | M1 | 座位属性和启停 |
| D | `POST /internal/v1/notifications` | M1/M2/M3 | 持久化接收、事件去重、投递失败重试 |
| D | `POST /internal/v1/email-notifications` | M1/M2/M3 | 独立邮件任务、事件去重、SMTP失败重试；真实通道需显式配置 |
| D | `POST /internal/v1/audit` | M1/M2/M3 | 事件去重、权限、可追溯 |
| M1 | `GET /internal/v1/rooms/{id}/availability` | M2 | 按时段预检查，不构成占用承诺 |
| M1 | `PUT /internal/v1/room-occupancies/{applicationId}` | M2 | 原子占用或释放、去重、防乱序 |
| M1 | `GET /internal/v1/room-occupancies/{applicationId}` | M2 | 超时后查询并恢复 |
| M1 | `PUT /internal/v1/maintenance-blocks/{workOrderId}` | M3 | 工单独立限制、去重、防乱序 |

## 公共约定草案

- 外部 API 与内部 API 分开；内部 API 也认证，校验调用服务的来源权限。
- D 主数据采用稳定 UUID；跨模块仍作为不透明字符串传输，业务对象由各模块生成。
- 时间使用带偏移的 ISO 8601，存储绝对时间，按 `Asia/Shanghai` 展示；时段为 `[startAt, endAt)`。
- 整教室占用请求含教室、起止时间、来源编号、目标状态、递增操作版本和 traceId。
- M1 按调用身份校验来源类型；相同来源和版本相同内容重复请求返回同一结果，不同内容拒绝。
- 旧版本请求不得复活已撤销的占用；释放后保留足够的版本记录。
- M1 对个人预约与整教室占用采用共同的原子并发控制；跨表规则不能只靠一张表的唯一约束。
- 区分 401、403、404、409、参数错误和暂时不可用；D 的错误体、分页与重试约定见实现契约；其余模块共同评审。
- 业务模块在本地事务中保存待发送事件；D 接收后按 eventId 去重，失败可重试。
- 开放时间、资源停用变更的传播与生效规则待评审，防止旧快照继续允许新预约。

## 必须验证的链路

1. M2 最终审批调用 M1，成功占用后才显示已批准；个人预约不能抢占同一资源。
2. M2 请求超时后查询同一来源结果；重试不生成第二份占用。
3. 撤销后的旧创建请求重试，不能恢复占用。
4. 同一教室两个维修工单，仅解除一个仍不可预约。
5. 维修全部解除但仍有 M2 占用，该时段仍不可个人预约。
6. 维修影响既有个人预约时，不把被迫取消或无法签到记为学生违约。
