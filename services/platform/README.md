# D 公共层

负责人：`dianashiba`。备用评审人待指定。

拥有：身份认证、角色权限及数据范围、空间座位主数据、通知投递、审计归集。
业务审批规则归 M2；预约和资源占用归 M1；工单生命周期归 M3。
各模块负责自己的权限检查、通知触发时机、业务事件和可靠发送。

已实现 Java 21 / Spring Boot 3.5 / PostgreSQL 17 应用：

- 账号认证、BCrypt、数据库哈希会话、CSRF、登录锁定、退出及改密。
- 角色与院系范围、自定义角色、账号和院系管理。
- 校区/楼栋/教室/座位、属性、每周开放时段、逻辑停用、版本冲突。
- 持久化站内信、去重、重试、失败队列、本人已读状态。
- 只增审计、服务来源认证、跨服务会话验证与主数据读取。

本目录运行 `./mvnw verify`。集成测试启动真实 PostgreSQL 17.11，不支持 root 用户运行；Apple Silicon 使用原生 ARM 二进制。

正式启动先准备 PostgreSQL，设置 `DATABASE_URL`、`DATABASE_USER`、`DATABASE_PASSWORD` 和首次初始化的 `BOOTSTRAP_ADMIN_PASSWORD`，运行 `./mvnw spring-boot:run`。
初始数据库只有 admin 和两个可重命名的启动院系，没有默认可用密码；以后启动不覆盖现有管理员密码。

默认 `COOKIE_SECURE=true` 用于 HTTPS；本地 HTTP 须显式设置 false。M1/M2/M3 独立服务凭据由对应 `M1_SERVICE_KEY` 等环境变量注入，空值禁用调用方。禁止将服务凭据放入前端。

根目录 `scripts/run-local-demo.sh` 提供临时演示。样例空间在 test 源码 LocalDemo 中，不会打进正式 jar。细节见 [D 契约](../../contracts/platform-api.md) 和 [D 范围](../../docs/requirements/d.md)。
