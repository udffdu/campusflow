# M1 预约提醒契约（CF-M1-008 / CF-M1-009）

状态：实现稿，2026-10-30。M1 负责判断业务触发时机和保存 outbox 事实；D 负责站内信与邮件任务的持久化、去重和投递。当前用户已授权本轮必要的 D/API/schema 变更，但尚不能据此推定 D 负责人完成评审。

## 触发规则

- `RESERVATION_START_REMINDER`：预约仍为 `CONFIRMED`，且服务端时钟进入 `[startAt-15分钟, startAt)` 时生成一次。
- `RESERVATION_LATE_REMINDER`：预约开始满 10 分钟后仍为 `CONFIRMED` 时生成一次；已经签到或进入其他状态的记录不生成。
- 时间按绝对时间保存，用户文案按 `Asia/Shanghai` 展示完整起止时间。
- 每个预约与提醒类型在数据库中唯一。调度器重跑、并发扫描或进程重启不会创建第二个业务提醒事实。
- 创建预约时已经错过提醒点的产品规则仍待单独评审；当前实现不通过增加最少提前预约时长规避该问题。

## M1 到 D

M1 分别调用以下内部接口；两次调用使用同一个稳定业务 `eventId` 和相同载荷：

| 接口 | 作用 |
| --- | --- |
| `POST /internal/v1/notifications` | 持久化站内信任务 |
| `POST /internal/v1/email-notifications` | 持久化邮件任务 |
| `GET /internal/v1/notifications/{id}` | 查询本服务来源的站内信回执 |
| `GET /internal/v1/email-notifications/{id}` | 查询本服务来源的邮件回执 |

请求沿用 D 的 `NotificationEvent`：`eventId,recipientId,title,body,objectType,objectId`。提醒标题和正文包含提醒类型、教室、座位、上海时区起止时间及下一步操作。教室和座位名称由 M1 在投递时通过 D 的公开内部资源契约读取，不跨库读取 D 表。

站内信与邮件是两个独立的持久化任务。任一通道接收失败，M1 outbox 保持待发送并按指数退避重试；网络调用不在数据库事务内。M1 outbox 使用租约和 `SKIP LOCKED` 支持崩溃恢复与多工作进程。

## 交付语义

- 业务提醒事实按 `(reservationId,eventType)` 去重；D 各通道按 `(source,eventId,recipientId)` 去重，载荷冲突返回 `409`。
- D 站内信写入与任务完成同事务，用户可在 `/notifications` 查看并标记已读。
- D 邮件任务使用租约、失败原因白名单、指数退避和最多五次尝试；缺少收件地址、收件人停用、通道关闭或 SMTP 失败均保留可观测状态。
- SMTP 在“服务器已接收邮件但客户端尚未记录成功”时无法提供严格 exactly-once。系统保留稳定任务 ID 和稳定 `Message-ID` 以帮助下游去重，但整体应描述为至少一次尝试，不得承诺绝不重复。
- 前端弹窗每 3 秒轮询站内信。首次加载建立历史基线，不弹出旧消息；之后每条新通知只弹一次，10 秒自动关闭，仍以服务端收件箱和已读状态为准。这不是 WebSocket 推送。

## 配置

M1 默认关闭后台提醒，显式设置 `M1_REMINDERS_ENABLED=true` 后启用；扫描周期、初始延迟、租约、重试和批量上限均由 `M1_REMINDER_*` 环境变量配置。

D 邮件通道默认关闭。真实 SMTP 至少配置：

```text
PLATFORM_MAIL_ENABLED=true
PLATFORM_SMTP_HOST=...
PLATFORM_SMTP_PORT=...
PLATFORM_MAIL_FROM=...
```

认证及传输按服务商要求使用 `PLATFORM_SMTP_USERNAME`、`PLATFORM_SMTP_PASSWORD`、`PLATFORM_SMTP_STARTTLS`、`PLATFORM_SMTP_SSL`。用户邮箱由管理员维护，初始化管理员可使用 `BOOTSTRAP_ADMIN_EMAIL`。密码和授权码只能通过环境变量或密钥服务注入，不得写入仓库。

## 验证入口与限制

- 正式调度、去重、失败重试和双通道隔离检查：`bash scripts/check-m1-reminders.sh`。
- 页面与本地模拟邮箱：`bash scripts/start-m1-reminder-preview.sh`，邮箱位于 `http://127.0.0.1:8025`。
- 人工预约后立即生成两类提醒：`bash scripts/start-m1-instant-reminder-preview.sh`。该脚本只使用测试 classpath 的 `triggerReminderPair` 加速器，不进入生产 jar，也不改变预约时间；它不能证明 15/10 分钟调度正确。
- 真实 SMTP 可用 `bash scripts/start-m1-reminder-preview.sh --real-mail`，还需设置 `CF_REMINDER_EMAIL` 和上述 SMTP 环境变量。

本地模拟 SMTP、站内信和前端弹窗已具备验证路径；真实外部邮箱服务商接收、D 负责人评审、远程 CI 和部署仍待完成。
