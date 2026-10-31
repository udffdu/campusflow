# M1 每日教室码、本人签到与座位使用契约

日期：2026-10-31。状态：CF-M1-007 已实现，当前页面流程已由用户人工确认；CF-M1-010 已补充开始后15分钟的签到截止、未签到终结和自动完成规则。CF-M1-008/009 见[提醒契约](m1-reminder-api.md)，CF-M1-011 仍未实现。尚未 push、PR、远程 CI 或部署。

## 安全与配置

普通 M1 写入口启用时，除[个人预约配置](m1-personal-api.md)外还必须提供 `M1_CHECKIN_SECRET`：至少 32 个随机字节的 Base64，不能与 `M1_CSRF_SECRET` 或 `M1_SERVICE_KEY` 复用。多实例必须使用同一值，轮换会立即改变当日所有教室码。密钥和动态码不写数据库、不进入日志、事件或错误响应。

签到码使用 HMAC-SHA256 从独立密钥、稳定教室 UUID、版本化用途域和上海日期派生为 6 位数字。日期固定按 `Asia/Shanghai`，每天 00:00 轮换；错误教室和错误日期得到不同编码。教室码响应带 `Cache-Control: no-store`。

本阶段不修改 D 权限目录：

- 学生查看本人使用状态、签到和退座要求有效 D 会话及既有 `seats.reservations.read.own`，M1 仍强制按会话用户查询本人预约；
- 教室码展示要求既有 `spaces.write`，且教室归属院系必须等于会话用户院系；
- D 不可用、身份失效或授权撤销时失败关闭；不信任请求体身份，也不读取 D 数据库。

独立的签到/展示权限属于后续可评审的最小权限增强，不是 CF-M1-007 当前实现的前置条件。ADMIN 跨院系展示未由 D 提供资源范围声明，本阶段同样按本院系限制，不能从角色名推定全局范围。

## 教室码展示

```http
GET /api/m1/v1/rooms/{roomId}/check-in-code
Cookie: CF_SESSION=...
```

成功响应：

```json
{
  "roomId": "10000000-0000-0000-0000-000000000001",
  "roomCode": "A101",
  "roomName": "第一教室",
  "code": "012345",
  "validDate": "2026-10-30",
  "expiresAt": "2026-10-30T16:00:00Z",
  "timeZone": "Asia/Shanghai"
}
```

无会话返回 401，无 `spaces.write` 或跨院系返回 403，教室不存在返回 404，教室停用返回 409，D 不可用返回 503。Web 路由为 `/room-checkin-display/:roomId`；空间管理详情只向有权且在范围内的启用教室显示入口。无人值守设备注册和二维码不在当前范围。

## 本人签到

```http
POST /api/m1/v1/reservations/{reservationId}/check-in
Content-Type: application/json
Origin: https://configured.example
X-M1-CSRF-Token: ...
Cookie: CF_SESSION=...

{"code":"012345"}
```

请求体只允许一个字符串字段 `code`，不接受 `userId`、`roomId`、座位、状态或版本。沿用普通 M1 的精确 Origin、Fetch Metadata、会话绑定 CSRF 和 1 KiB 请求体上限。

签到截止点为`startAt + 15分钟`。截止点前只有`CONFIRMED`可首次签到；正确的当日教室码会把预约原子更新为`CHECKED_IN`、版本加一，并写入`reservation_transitions`和唯一`RESERVATION_CHECKED_IN` outbox事实。到达或超过截止点时，签到事务在同一用户/教室锁内先执行幂等的`CONFIRMED -> NO_SHOW`终结，再返回状态冲突；不会让迟到请求复活预约。若签到事务先在截止点前成功，后续超时扫描看到`CHECKED_IN`就不会记违约。重复签到返回当前记录及`idempotent=true`，不新增转换或事件；其他终结状态不能被签到复活。

错误码按用户和预约记录在 M1 数据库：15 分钟失败统计窗口内，第 5 次错误后封锁 15 分钟；所有实例共享，重启不失效。封锁期间即使提供正确码也返回 429；成功后清理该预约的失败记录。前端收到无效码后清空输入并允许重新输入。响应区分：

| HTTP | code | 含义 |
| --- | --- | --- |
| 404 | `RESERVATION_NOT_FOUND` | 不存在或不属于当前用户，统一返回以避免枚举他人记录 |
| 409 | `CHECK_IN_STATE_CONFLICT` | 当前状态不允许首次签到 |
| 422 | `CHECK_IN_CODE_INVALID` | 编码、教室或日期不匹配，可重新输入 |
| 429 | `CHECK_IN_RATE_LIMITED` | 错误尝试达到限制 |
| 503 | `STORAGE_UNAVAILABLE` / `D_UNAVAILABLE` | 数据库、锁或 D 不可用 |

## 使用状态

签到成功后，Web 跳转到 `/seat-usage/:reservationId`。页面通过下列接口重新读取服务端保存的签到/退座时刻，刷新页面不会丢失计时状态：

```http
GET /api/m1/v1/reservations/{reservationId}/usage
Cookie: CF_SESSION=...
```

成功响应沿用本人预约字段，并增加：

```json
{
  "status": "CHECKED_IN",
  "checkedInAt": "2026-10-30T08:01:02Z",
  "completedAt": null,
  "serverTime": "2026-10-30T08:03:04Z"
}
```

页面用 `checkedInAt` 和 `serverTime` 校准使用时长，不信任浏览器本地时间。退座后 `status` 为 `COMPLETED`，`completedAt` 固定计时终点。接口仅接受 `CHECKED_IN` 或 `COMPLETED` 的本人记录；非本人统一返回 404，尚未进入使用状态返回 `LEAVE_SEAT_STATE_CONFLICT`。

## 主动退座

```http
POST /api/m1/v1/reservations/{reservationId}/leave-seat
Origin: https://configured.example
X-M1-CSRF-Token: ...
Cookie: CF_SESSION=...
```

请求无业务请求体。只有本人 `CHECKED_IN` 记录可首次退座；成功后原子更新为 `COMPLETED`、版本加一，写入原因 `USER_LEAVE_SEAT` 的转换和唯一 `RESERVATION_COMPLETED` outbox 事实，并返回包含 `completedAt`、`serverTime` 和 `idempotent=false` 的使用状态。对已经 `COMPLETED` 的同一记录重复退座返回 `idempotent=true`，不重复写入。其他状态返回 409 `LEAVE_SEAT_STATE_CONFLICT`。

签到和退座写入都先按既有顺序取得用户锁和教室锁，再在锁内重读本人记录、在线复核权限并以状态和版本作条件更新。状态、转换和 outbox 任一写入失败会整体回滚。`CHECKED_IN` 属于有效个人占用；主动退座成为 `COMPLETED` 后不再阻止新预约。
