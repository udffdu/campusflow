# M3 上报与本人查询：决定和实现说明

2026-11-02：用户逐项采用原六组建议，并明确采用补全方案。此记录表示用户确认本任务内部实现，不表示另一位 M3 成员、D 或共享入口负责人已经评审。不将需求整体标 accepted。

| 组 | 本次采用 |
| --- | --- |
| 分类与输入 | LIGHTING/POWER/FURNITURE/HVAC/OTHER；Java strip 去首尾空白，5–1000 Unicode 码点；无照片/新增联系字段；未知字段及客户端 reporterId/actorId/departmentId/status 拒绝 |
| 范围与权限 | maintenance.report / maintenance.read.own；上报院系来自 D 房间，提交该院系再次授权；普通用户本人院系，D 返回 ADMIN 且业务授权成功才可跨院系；本人查询对 ADMIN 同样只看本人 |
| 状态与查询 | 服务端 UUID、UTC 时间、PENDING_DISPATCH；分页 page=0 起、size=1–100，默认 20；createdAt/id 倒序；详情含创建历史；他人详情 404；不接受自报身份等额外查询参数 |
| 工程与隔离 | services/maintenance，Java 21 / Spring Boot 3.5.16 / JDBC / Flyway / PostgreSQL；默认端口 8083，前缀 /api/v1/maintenance；独立 PostgreSQL 数据库及专用角色，必须显式提供连接配置，不继承 D 数据库默认值 |
| CSRF | M3 独立 GET /api/v1/maintenance/csrf；Cookie M3-XSRF-TOKEN、Path=/api/v1/maintenance、SameSite=Strict、默认 Secure；请求头 X-M3-XSRF-TOKEN；不接受 D 的 XSRF-TOKEN/X-XSRF-TOKEN 代替；同源代理上线前评审 |
| 幂等与事件 | Idempotency-Key 为 1–128 个 ASCII 字母数字或 ._:-；按 roomId、faultType、strip 后 description 比较；同用户创建动作内唯一；首轮无硬删除/自动清理；只写创建审计 outbox，通知收件人未定不生成通知 |

CSRF 采用 Spring CookieCsrfTokenRepository 的独立 double-submit 机制；D 的登录/退出不会自动清除 M3 Cookie，客户端每次登录/退出后重新获取 M3 CSRF。身份和权限仍每次实时调用 D，不因持有 CSRF 获得访问权。正式域名、Cookie 安全和网关路径须经 D/入口方评审。服务间密钥仅从环境变量获取。

四张表均为 M3 自有：work_orders、work_order_history、creation_results、audit_outbox。无跨模块外键或 SQL。创建用唯一约束竞争幂等键，工单/历史/稳定事件 ID/完整响应同事务提交；数据库回滚后同键可重试。比起先查询键再插入，此方式由 PostgreSQL 约束处理并发。列表和 total 在同一只读 REPEATABLE_READ 事务内取数。

独立数据库比共享 schema 更易审核数据库权限边界，但需要部署负责人创建数据库与专用非超级用户角色；当前测试使用临时 PostgreSQL 的测试管理员，尚未验证生产角色权限。不得将 M3 连接指向 D 数据库。V1 是新增迁移；将来扩展状态须添加新迁移，不重写已应用版本。业务 jar 无演示数据。

创建审计 payload 不含完整描述、Cookie 或服务密钥。outbox 当前仅持久化，未实现投递 worker；delivered_at 为空不表示 D 已接收。可靠投递后续独立任务处理，本次不扩展。没有修改前端、D、M1 或管理/派单接口。

外部待办：D 权限目录和角色映射（包括谁被授予报修及本人查询权限）、服务密钥发放、同源入口/CSRF 评审、部署数据库角色；M3 同伴检查字段及后续迁移交接。报修前实时检查 ROOM/effectiveActive；检查后到提交间房间被停用的分布式竞态尚需 D 确认，无跨服务锁或全局原子性承诺。重试仍先执行当前授权和房间有效性校验，权限撤销或房间停用会拒绝，不保证这种情形下重放旧响应。

验证及演示见 [验证记录](../process/m3-reporting-validation.md) 和 [服务 README](../../services/maintenance/README.md)。
