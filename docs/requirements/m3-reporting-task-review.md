# 上报与本人查询后端：实施前核查

> 2026-11-02 后续更新：用户已采用上报与本人查询六组内部建议及补全方案，见 [决定说明](m3-reporting-decisions.md)。本文件原待决表/核查状态保留为历史依据；当前实现与测试见 [验证记录](../process/m3-reporting-validation.md)。外部评审及整体验收未完成。
日期：2026-11-02。状态：已授权有限实施，必要规则待人工决定；尚未实现，不是验收通过记录。

本任务仅含最小工程/迁移、上报、本人分页与详情、D 身份授权/空间客户端和后端测试。排除页面、管理查询、派单、维修、超时、验收评价、M1 状态变更及 D 修改。

## 当前证据

- 开始时已有未提交修改：AGENTS.md、spec/requirements.json；未跟踪的 m3-context.json 及四份 M3 需求文档。保留这些内容，不回退、不提交。
- services/maintenance 只有 README。D 使用 Java 21、Spring Boot 3.5.16、JDBC、Flyway、PostgreSQL；PlatformIntegrationTest 使用真实嵌入式 PostgreSQL、MockMvc 和事务/并发测试。
- D V2 权限目录只有 users.manage、notifications.manage、spaces.read、spaces.write、audit.read；没有 M3 业务权限。InternalController 即使 allowed=false 也可能返回 identity，客户端必须显式检查 allowed=true。
- D 的 CSRF 契约只描述 D 浏览器接口，不能推出其令牌适用于 M3。
- m3-joint-decisions.md 第 10 节确认表为空；m3.md 中状态、字段和表结构仍是建议。

## 请人工决定的最小集合

1. B02/B03：分类、描述计数/长度、照片与联系字段是否纳入。现有建议为五类、trim 后 5–1000 Unicode 码点、不含照片和新增联系字段。
2. B04/A07：报修是否限本人院系；D 上报和本人查询权限名及角色映射。建议名 maintenance.report / maintenance.read.own 尚不存在；需要 D 负责人评审，实际登记由 D 的授权任务完成。
3. L01/B06/A04：初始状态是否 PENDING_DISPATCH；UUID、分页 0 起/1–100、稳定倒序、详情含创建历史、他人详情 404 是否采用。
4. A01–A03：services/maintenance 工程路径、端口/API 路由、独立数据库或独立 schema/数据库角色；工单、历史、幂等结果、outbox 的字段需 M3 双方评审。不能继承 D 默认数据库凭据。
5. A05：与 D/共享入口确认 M3 CSRF 的令牌取得接口、Cookie 名/Path、请求头、同源策略和登录退出后的行为。可以评审 M3 独立令牌方案，但本次不自行确认。
6. B09/B10/A06：幂等键格式/保留期、内容规范化规则，以及创建时哪些审计/通知事件必要、通知收件人。不把无限保留或任意事件类型默认为已确认。

用户本次已明确 ROOM/effectiveActive、服务端受控字段、原子性、幂等语义和本人隔离，不再就这些原则请求确认。外部评审可以仍待办；内部测试需先确定对应规则，真实联调必须另行报告。

## 待评审实施方案与文件

- services/maintenance/pom.xml、启动类与 application.yml：复用 D 依赖版本；连接、端口、D 地址及密钥从环境注入，不复制 D 实体或 bootstrap 数据。
- D 客户端：限时 HTTP 调用 authorization/resources；会话无效、allowed 非 true、响应畸形及网络失败均拒绝；不记录请求凭据。
- Controller/Service/Repository：拒绝未知/受控字段；查询 SQL 的列表、count、详情均以真实 reporterId 限制。创建事务用唯一约束串行化同用户同键竞争，保存可重放响应、工单、历史与必要 outbox。
- db/migration/V1__maintenance.sql：仅在隔离和字段评审后新增；演示 seed 放测试源，不修改 D 或已应用迁移。
- src/test：真实 PostgreSQL 验证 SQL/事务/迁移；HTTP 替身仅代替 D，不能 mock 本人过滤或事务；另有真实 D 联调记录。

## 验收记录（实际状态）

| 项 | 要执行的验证 | 当前结果/命令 |
| --- | --- | --- |
| A | 创建后 SQL 与本人详情核对服务端字段 | 未执行：接口/表未实现，无可执行业务命令 |
| B | 非法输入、非 ROOM、父级停用、伪造字段，核对零落库 | 未执行：规则待确认 |
| C | 会话失效/无权限/D 超时及错误，读写均拒绝 | 未执行：权限/CSRF 契约待确认 |
| D | A/B 用户列表、详情、total 与参数攻击 | 未执行：接口未实现 |
| E | 同键顺序/并发重试和不同内容冲突 | 未执行：持久化未实现 |
| F | 事务各阶段注入失败，四类记录均回滚 | 未执行：迁移未实现 |
| G | 空库 migrate 后再次 migrate，无重复变更 | 未执行：schema 尚未评审 |
| H | 临时移除本人 SQL 过滤，指定测试必须失败；恢复再通过 | 未执行：尚无被测实现，不能编造变异证据 |

真实 PostgreSQL 测试：未执行。D 替身测试：未执行。真实 D 联调：未完成。仓库结构检查不能代表任何 A–H 通过。

## 后续人工接口演示顺序（未执行）

契约确认并实现后才能填写确切 URL、请求头及命令：准备独立空库并重复迁移；设置 D 地址与服务密钥环境变量；使用两个真实有业务权限的 D 用户登录；按已确认方案取得 M3 CSRF；A 提交含幂等键的上报，记录响应 ID；SQL 核对工单/历史/幂等/outbox；A 查询详情/列表；B 猜测 ID 并检查其列表 total；A 重试同键同内容及不同内容；最后执行失败、并发、迁移与变异测试并保存报告。不得把 D 的测试用户或替身结果称为真实联调。
