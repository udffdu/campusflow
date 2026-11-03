# M3 上报与本人查询后端

当前后端包含上报、本人列表/详情、受权管理列表/详情、D客户端和本地审计outbox。现有首次派单和可配置启用的outbox投递worker（默认关闭）；前端页面位于apps/web。规则见 [决定说明](../../docs/requirements/m3-reporting-decisions.md)，测试证据见 [验证记录](../../docs/process/m3-rebuild-task1a-validation.md)。

## 启动

要求 Java 21+、PostgreSQL。由部署方准备 M3 独立数据库及专用角色，角色仅拥有该库 M3 表。设置以下环境变量（不将真实值写进仓库或命令历史）：

- M3_DATABASE_URL：jdbc:postgresql://数据库地址:5432/campusflow_m3
- M3_DATABASE_USER / M3_DATABASE_PASSWORD：M3 专用角色
- M3_D_URL：D 内部服务根地址，正式使用受信网络/TLS
- M3_SERVICE_KEY：D 发放的 M3 服务密钥，至少 32 字符
- M3_COOKIE_SECURE：默认 true；仅本机 HTTP 演示设 false
- M3_PORT：默认 8083

在本目录运行 `./mvnw spring-boot:run`（Windows `./mvnw.cmd spring-boot:run`）。Flyway 启动时执行版本化迁移，重启不会重复建表。没有生产种子或默认账户。配置缺失将拒绝启动；禁止指向 D 数据库。已有 Maven 可用 `mvn -f services/maintenance/pom.xml verify` 在仓库根执行测试。

## 人工接口演示（真实 D 条件具备后执行）

以下为 curl 命令模板，尚未完成真实 D 演示。先由 D 负责人登记 maintenance.report / maintenance.read.own 并授予两名测试用户，准备有效 ROOM。通过已评审的同源入口登录 D，使用 D 登录返回的会话 Cookie jar；不要手动填写 reporterId。不要用普通 spaces.read 权限替代 M3 权限。

PowerShell 中，`$base` 是将 /api/v1/maintenance 转发给 M3 的同源入口；`$jar` 是当前用户登录 D 的私有临时 Cookie 文件；`$room` 是 D 有效房间 UUID。保护临时 Cookie 文件，不提交、不打印其内容。当前仓库没有替你修改共享代理。

```powershell
$csrf = curl.exe -sS -b $jar -c $jar "$base/api/v1/maintenance/csrf" | ConvertFrom-Json
$body = @{roomId=$room;faultType='LIGHTING';description='教室照明灯无法正常点亮'} | ConvertTo-Json -Compress
$bodyFile = Join-Path $env:TEMP 'm3-demo-body.json'
[IO.File]::WriteAllText($bodyFile,$body,[Text.UTF8Encoding]::new($false))
$key = [guid]::NewGuid().ToString()
$result = curl.exe -sS -b $jar -H "X-M3-XSRF-TOKEN: $($csrf.token)" -H "Idempotency-Key: $key" -H 'Content-Type: application/json' --data-binary "@$bodyFile" "$base/api/v1/maintenance/work-orders" | ConvertFrom-Json
$result
curl.exe -sS -b $jar "$base/api/v1/maintenance/work-orders?page=0&size=20"
curl.exe -sS -b $jar "$base/api/v1/maintenance/work-orders/$($result.id)"
```

1. 核对返回 201、真实 reporterId、D 房间院系、PENDING_DISPATCH、UTC 时间和创建历史。
2. 在 M3 数据库用返回 ID 查询以下四表；不查询 D 表。
3. 原 key/原 body 再提交，应返回原 201/响应；改 description 用同 key 应 409。
4. 换成用户 B 的 Cookie jar：A 的详情应 404，B 列表和 total 不含 A；加 reporterId 参数应 400。
5. 去掉 M3 CSRF 应 403；用 D 的 CSRF 头不能替代；失效会话应拒绝；停止 D 后受保护读写应 503。
6. 登录、退出后重新 GET M3 csrf；不要认为 D 自动重置 M3 CSRF Cookie。

```sql
-- 将 UUID 替换成创建响应中的工单 id；在 M3 库执行
SELECT * FROM work_orders WHERE id = '返回的UUID';
SELECT * FROM work_order_history WHERE work_order_id = '返回的UUID';
SELECT * FROM creation_results WHERE work_order_id = '返回的UUID';
SELECT event_id, work_order_id, created_at, delivered_at FROM audit_outbox WHERE work_order_id = '返回的UUID';
```

outbox 存在只表示原子持久化，delivered_at 为空，不表示 D 已收到审计。D 权限或入口评审未到位时只运行内部测试，不放宽鉴权演示。

重建任务1A：完整详情遵循 contracts/m3-work-order-format-v1.md；V1映射派单字段null、version=1、updatedAt=createdAt。早期1A仅用V1测试库；当前任务3使用隔离V1→V2升级/全新V2测试库，不连接业务库，不能用切换源码降级数据库。

重建任务1已增加 GET /api/v1/maintenance/management/work-orders（page/size/status）及 /{id}。仅允许maintenance.read.scope获权身份，非ADMIN按院系过滤，详情越范围404。仅支持待派单状态，尚无派单/独立历史接口，候选接口见下方任务2说明；见 [本阶段验收](../../docs/process/m3-rebuild-management-task1-validation.md)。

重建任务2新增GET /api/v1/maintenance/management/work-orders/{id}/candidates，要求maintenance.dispatch；生产PendingCandidateProvider明确503/D_CANDIDATES_NOT_READY。正式D接口仍待评审，不修改V1、不产生派单动作。见 [任务2验收](../../docs/process/m3-rebuild-candidates-task2-validation.md)。

任务3新增POST /api/v1/maintenance/management/work-orders/{id}/dispatch，JSON仅assigneeId/expectedVersion，独立CSRF与Idempotency-Key。生产资格默认503；M3_AUDIT_ENABLED默认false，启用前须D确认。当前真实派单字段从V2读取，不再固定null；详见 [任务3验收及审计恢复](../../docs/process/m3-rebuild-dispatch-task3-validation.md)。
