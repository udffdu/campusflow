# M1 预约运营查询契约（CF-M1-011）

日期：2026-10-31。状态：本地实现与人工页面验收完成；尚未提交、push、PR、远程 CI 或部署。该能力用于任务书所述“管理员通过查看自习室和座位的预约情况来调整管理策略，提升资源利用率”，不提供学生画像或跨院系数据导出。

## 权限与范围

- D 权限目录登记独立权限`seats.operations.read`，但不自动授予`ADMIN`、`MEMBER`或其他既有角色；必须由有权的 D 管理员显式配置运营角色。
- M1 每次请求都通过 D 的公开内部接口校验当前会话、权限与院系，不信任请求中的用户或院系字段。
- 可见范围为当前身份院系下的教室及其座位。指定范围外的`roomId`或`seatId`返回 403，而不是退化为全局查询。
- 接口只读，使用只读可重复读事务；不会改变预约、签到、违约、通知或资源状态。
- 响应不包含预约人的用户编号、姓名、账号、邮箱或其他身份信息。

## 查询接口

```http
GET /api/m1/v1/operations/reservations?from=2026-10-25T16:00:00Z&to=2026-10-31T16:00:00Z&size=20
Cookie: CF_SESSION=...
```

只允许以下查询参数：

| 参数 | 必填 | 规则 |
| --- | --- | --- |
| `from`、`to` | 是 | UTC `Z`时间；查询区间为`[from,to)`，`from < to`且最长31天 |
| `roomId` | 否 | 必须属于当前院系范围 |
| `seatId` | 否 | 必须属于当前院系范围；同时指定教室时二者必须匹配 |
| `status` | 否 | `CONFIRMED`、`CHECKED_IN`、`COMPLETED`、`NO_SHOW`或`DISRUPTED` |
| `size` | 否 | 1～100，默认20 |
| `cursor` | 否 | 服务端返回的不透明游标；与院系及当前筛选条件绑定，客户端不得构造或跨筛选复用 |

预约与查询区间有交集时计入，即`startAt < to && endAt > from`。列表按`startAt,id`倒序稳定排序，`nextCursor`为空表示没有下一页。单次请求中的列表和汇总来自同一个数据库快照；分页期间若业务数据发生变化，客户端应刷新第一页重新取得最新汇总。

成功响应示意：

```json
{
  "scope": {"departmentId": "10000000-0000-0000-0000-000000000001"},
  "criteria": {
    "from": "2026-10-25T16:00:00Z",
    "to": "2026-10-31T16:00:00Z"
  },
  "resources": {
    "rooms": [{"id": "...", "code": "A101", "name": "A101 本地演示"}],
    "seats": [{"id": "...", "roomId": "...", "code": "A101-01"}]
  },
  "items": [{
    "id": "...",
    "roomId": "...",
    "roomCode": "A101",
    "seatId": "...",
    "seatCode": "A101-01",
    "startAt": "2026-10-30T01:00:00Z",
    "endAt": "2026-10-30T02:00:00Z",
    "status": "COMPLETED",
    "checkedInAt": "2026-10-30T01:05:00Z",
    "completedAt": "2026-10-30T01:50:00Z",
    "usageDurationSeconds": 2700,
    "createdAt": "2026-10-29T00:00:00Z",
    "updatedAt": "2026-10-30T01:50:00Z"
  }],
  "size": 20,
  "nextCursor": "",
  "total": 1,
  "summary": {
    "total": 1,
    "byStatus": {"CONFIRMED": 0, "CHECKED_IN": 0, "COMPLETED": 1, "NO_SHOW": 0, "DISRUPTED": 0},
    "checkedIn": 1,
    "violations": 0,
    "eligible": 1,
    "checkInRate": 1.0,
    "reservedDurationSeconds": 3600,
    "actualUsageSeconds": 2700,
    "utilizationRate": 0.75,
    "byRoom": [{"id": "...", "code": "A101", "count": 1}],
    "bySeat": [{"id": "...", "code": "A101-01", "count": 1}]
  },
  "serverTime": "2026-10-31T08:00:00Z"
}
```

所有运营响应使用`Cache-Control: no-store`。

## 统计口径

- `total`及`byStatus`统计当前筛选范围内与查询区间相交的所有预约；状态筛选同时作用于列表与汇总。
- `checkedIn = CHECKED_IN + COMPLETED`，`violations = NO_SHOW`，`eligible = checkedIn + violations`。
- `checkInRate = checkedIn / eligible`；没有可评价记录时返回`null`，不会用0掩盖无样本。
- `reservedDurationSeconds`只累计`CONFIRMED`、`CHECKED_IN`和`COMPLETED`在查询区间内的预约时长。
- `actualUsageSeconds`只累计`CHECKED_IN`和`COMPLETED`从真实签到转换到完成时刻（使用中取服务端当前时刻）的区间，并裁剪到预约及查询区间；`DISRUPTED`不计入实际使用。
- `utilizationRate = actualUsageSeconds / reservedDurationSeconds`；分母为0时返回`null`。
- `byRoom`与`bySeat`按当前筛选结果计数，用于资源排行，不等同于物理容量占用百分比。

## 失败响应

| HTTP | code/条件 | 含义 |
| --- | --- | --- |
| 400 | 非法或未知参数、时间/范围/状态/游标错误 | 拒绝含糊查询，不忽略未知字段 |
| 401 | 无有效 D 会话 | 未认证 |
| 403 | 缺少`seats.operations.read`或资源越范围 | 未授权或院系范围不匹配 |
| 503 | D 或 M1 数据库不可用 | 失败关闭，不返回无范围数据或旧缓存 |

## 当前边界

该接口只分析 M1 个人预约事实。M2 整室占用、M3 维修限制、教室物理容量和开放时长尚未形成统一运营数据源，因此当前“预约时段实际利用率”不能解释为全校全部资源的综合利用率。真实生产规模的索引、查询预算和保留周期仍需在部署前按实际数据量验证。
