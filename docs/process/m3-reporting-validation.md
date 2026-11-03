# M3 上报/本人查询验证记录

日期：2026-11-02。结论：本地内部验证通过，真实 D 联调未完成，等待人工验收。不是远程 CI 或整体验收通过声明。

## 实际环境及命令

Windows PowerShell；现有 D:/oracleJdk-27 编译 release 21；Spring Boot 3.5.16；嵌入式真实 PostgreSQL 17.11。不是 H2。Maven 依赖缓存与 PostgreSQL 临时文件在 D:/CampusFlow-Tools；读取机器已有 Maven，不新增 C 盘安装。

仓库根目录执行（$mvn 是本次机器已有 Maven 绝对路径）：

```powershell
$env:JAVA_HOME='D:/oracleJdk-27'
$env:TEMP='D:/CampusFlow-Tools/tmp'
$env:TMP=$env:TEMP
$mvn='C:/Users/fgrhy/.m2/wrapper/dists/apache-maven-3.9.11/03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc/bin/mvn.cmd'
& $mvn -o '-Dmaven.repo.local=D:/CampusFlow-Tools/m2/repository' '-DargLine=-Djava.io.tmpdir=D:/CampusFlow-Tools/tmp' -f services/maintenance/pom.xml verify
```

最终退出 0；Tests run: 8, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS。报告在 services/maintenance/target/surefire-reports/，完整日志在 target/verify-run.log（构建产物忽略入库）。此前首次离线测试因缺 Surefire 依赖失败；获准在 D 盘缓存下载后运行成功。首次受限环境变异运行因 PostgreSQL 初始化失败，不作为有效变异证据；放行同一临时目录后重跑才得到下述断言失败。

## A–H 实际证据

以下 A–G 全部由上面的 verify 命令执行。可用 `-Dtest=ReportingTest#方法名` 单独复现（替换 verify 为 test）。

| 验收 | 测试方法/结果 |
| --- | --- |
| A | A_persistence：201 响应及本人详情核对 ID、报修人、院系、状态/历史；SQL 校验报修人；四表各一条，通过 |
| B | B_validation：伪造 reporterId/actorId/departmentId/status、未知分类、纯空白、4/1001 码点、无效/非 ROOM/父级有效性为 false/跨院系拒绝并验证四表为空；5/1000 个 emoji 边界成功，通过。父级停用由 D effectiveActive=false 替身模拟，未重复实现 D 层级逻辑 |
| C | C_authAndCsrf：未登录/失效/allowed=false 含 identity、D 503/畸形响应、缺 CSRF/D CSRF 不能替代，读写拒绝且无业务数据，通过 |
| D | D_ownerIsolation：A/B 工单，A 列表和 total 仅自己、他人详情 404、篡改 reporterId 400、翻页及非法分页，通过 |
| E | E_concurrentReplay：8 请求并发同键只一单且响应一致、strip 等价重试、不同内容 409；E_concurrentDifferentContent：两种内容同键并发恰好 201+409，通过 |
| F | F_rollback：分别在历史 INSERT、outbox INSERT、幂等结果 UPDATE 用真实 PG trigger 注入异常；四表全部回滚；去除注入同键可成功，通过 |
| G | G_emptyMigrationAndRepeat：全新临时 PostgreSQL 启动迁移成功；额外空 schema 第一次执行 1 次、第二次 0 次、validate 通过，无种子混入 |
| H | 暂将列表 SQL 的 reporter_id=? 替换为 ?::uuid is not null，保留参数绑定，单跑 D_ownerIsolation；退出 1，Tests run: 1, Failures: 1, Errors: 0，明确断言 items.length expected 1 but was 2。finally 恢复原源码后完整 verify 8/8 通过 |

H 实际测试命令：在上述环境下执行

```powershell
& $mvn -o '-Dmaven.repo.local=D:/CampusFlow-Tools/m2/repository' '-DargLine=-Djava.io.tmpdir=D:/CampusFlow-Tools/tmp' '-Dtest=ReportingTest#D_ownerIsolation' -f services/maintenance/pom.xml test
```

仅在本地临时改动 Orders.java 的上述 SQL 后执行；必须备份并 finally 恢复，然后再执行完整 verify。有效失败日志 target/mutation-run.log；最终源码已恢复，不提交破坏版本。

## 测试边界和未完成项

- 真实 PostgreSQL：SQL、唯一约束、并发、事务、版本迁移均实际执行。
- 外部替身：测试内 HTTP Server 模拟 D authorization/resources；使用真实 HTTP 客户端和服务 Bearer 头校验。业务、本人过滤和数据库未 mock。HTTP 503/畸形响应已测；真实网络超时、生产 TLS 和停用权限传播未联调。
- 真实 D：未完成。当前目录只有 D 公共权限，未修改 D；等待 D 负责人登记业务权限/角色、发放密钥及评审 CSRF/同源入口。没有绕过 allowed=true。
- 专用数据库角色部署、同伴 schema 评审、远程 CI/共享代理接入未完成。测试 PostgreSQL 角色不能当作生产最小权限验收。
- 审计仅保存在 M3 outbox，投递 worker 未实现；无通知收件人方案，不生成通知。派单/管理查询/页面全部不在本任务。
- 服务前置校验与创建提交不是跨 D/PostgreSQL 分布式事务；资源并发停用策略待外部确认。重放仍校验当前身份和房间有效性。

人工演示的具体接口/命令见 [服务 README](../../services/maintenance/README.md)。需求 CF-M3-001 标 implemented，CF-M3-002 因管理查询未实现保留 in_progress，两者 baseline_status 均保持 proposed。
