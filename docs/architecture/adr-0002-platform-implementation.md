# ADR-0002：D 与前端实现

日期：2026-10-22。状态：用户授权实现，跨模块基线待组内评审。

用户要求直接完成 D，并确认前端仅覆盖 D 页面和公共外壳。保持独立 D 服务与一个 Vue 应用，不扩展 M1/M2/M3 业务。

采用 Java 21、Spring Boot 3.5.16、Spring JDBC、Flyway、PostgreSQL 17.11；前端 Vue 3.5.44 + TypeScript + Vite 8.3.5。
已核对 [Spring Boot 运行要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[Vue 起步指南](https://vuejs.org/guide/quick-start.html)、[Vite 运行要求](https://vite.dev/guide/)，准确依赖由 pom、Maven Wrapper 和 npm 锁文件固定。

选择数据库随机会话，令牌仅存 SHA-256，Cookie 为 HttpOnly/SameSite=Lax，生产 secure；每请求实时读取有效账号与最新权限。D 在线授权不可用时，不用过期快照放行写入。

自有数据库会话不使用 HttpSession/自动会话认证策略；登录、退出与改密显式重置 CSRF，前端下一次写入前重新获取令牌。依据 [Spring Security 会话管理](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html) 与 [CSRF 文档](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html) 配置，通过真实浏览器连续读取/写入回归验证。

内部服务独立凭据识别来源；业务服务在线验证用户会话，仍负责自己的业务权限、对象与院系归属。初版权限目录仅含公共层；2026-10-28当前用户授权直接补充三项个人座位权限/SEAT_USER、ROOM预约开放范围和座位插座标记，通过新增V3演进，说明见 [负责人交接](../handoffs/m1-integration.md)。其他业务权限与未决政策仍单独处理，不记作受影响负责人本人已评审。

通知使用持久化站内信队列与事务收件箱，不引入消息集群；业务模块负责自己的 outbox。审计只接收有界结构化字段，不记录完整请求；触发器防常规 update/delete，不承诺抵抗数据库管理员篡改。

空间使用稳定 UUID、不可重新挂载的层级、逻辑停用和乐观版本。父级停用动态影响 effectiveActive，version 不充当预约锁；M1 仍负责自己的原子占用控制。

D 备用评审人、全组角色映射、审计保留期限及华为云发布待组内决定，不虚构全组一致结论。
