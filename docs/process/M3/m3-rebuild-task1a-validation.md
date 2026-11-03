# M3重建任务1A验收包

日期：2026-11-03。按最终Format规则重建，不是历史版本精确恢复。关联CF-M3-001和CF-M3-002的本人查询部分。用户确认重建方案及本地提交流程；用户于2026-11-03回复“可以，继续做”，确认本阶段内部重建验收通过并授权本地提交；不代表真实D联调或外部schema评审通过。

## 实现与范围

- 新增可独立构建的maintenance Maven服务，复用Java21/Spring Boot/JDBC/Flyway/PostgreSQL及原有依赖。
- POST /api/v1/maintenance/work-orders、GET同路径分页、GET /{id}详情、GET /csrf。
- D实时验证CF_SESSION、allowed=true；上报读取真实ROOM/effectiveActive/院系并二次授权。受控字段和未知参数拒绝。
- 工单、创建历史、审计outbox和幂等响应在同一本地事务保存；同用户同键同内容返回原响应，不同内容409。
- 本人详情/list/count均按真实reporter过滤；列表及详情使用可重复读事务。
- V1逐字保留，仅四张自有表；无演示种子、管理、候选、派单、独立历史接口、页面或投递worker。
- 详情遵守最终14字段、创建历史8字段；列表无history。V1派单字段为空、版本1，updatedAt来自真实createdAt；resourceVersion仅内部保存。

## 实际命令

工作目录 D:/CampusFlow-Worktrees/m3-staged。使用机器已有工具与缓存，不增加项目依赖。

~~~powershell
$env:JAVA_HOME='D:/oracleJdk-27'
$env:TEMP='D:/CampusFlow-Tools/tmp'
$env:TMP=$env:TEMP
$mvn='C:/Users/fgrhy/.m2/wrapper/dists/apache-maven-3.9.11/03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc/bin/mvn.cmd'
& $mvn -o '-Dmaven.repo.local=D:/CampusFlow-Tools/m2/repository' '-DargLine=-Djava.io.tmpdir=D:/CampusFlow-Tools/tmp' -f services/maintenance/pom.xml verify
~~~

初次verify：退出0，10 tests，0 failures/errors/skipped，BUILD SUCCESS，生成可执行jar。日志services/maintenance/target/rebuild-1a-initial.log。

临时只将Orders列表SQL的 reporter_id=? 换为 ?::uuid is not null，保留参数绑定；上述Maven参数附 -Dtest=ReportingTest#D_ownerIsolation test：
退出1，1 test，1 failure，0 errors；断言 $.items.length() expected 1 but was 2。finally按字节恢复Orders.java。日志target/rebuild-1a-mutation.log。

恢复后尝试clean verify：离线缺maven-clean-plugin:3.4.1，未进入测试，不能计为测试失败或通过。保留target/rebuild-1a-clean-unavailable.log；不新增依赖，改用既有verify重新编译并复测。最终verify：退出0，10 tests，0 failures/errors/skipped，BUILD SUCCESS；日志target/rebuild-1a-final.log，报告target/surefire-reports。恢复后确实重新编译8个主源码文件和1个测试源码文件。

仓库检查：在根目录执行 python scripts/check_repository.py，退出0，4 modules、18 requirements；仅检查布局与登记引用。前端/D业务源码相对基线无改动，本阶段未重跑其测试。

## A–H与补充断言

| 项 | 测试/数据证据 |
| --- | --- |
| A 持久化与本人详情 | A_persistence：响应、真实PG工单reporter、四表各一条、详情一致 |
| B 无效输入和房间 | B_validation：受控字段、未知类型、长度、无效/SEAT/停用/越院系拒绝；四表为空；5/1000码点边界 |
| C 身份/权限/依赖 | C_authAndCsrf：未登录、失效、allowed=false含identity、D503/畸形响应、缺CSRF或D令牌替代均拒绝 |
| D 本人隔离 | D_ownerIsolation：A/B数据、列表/total、详情404、伪造参数400及翻页 |
| E 幂等并发 | E_concurrentReplay/E_concurrentDifferentContent：8并发同键一单、原响应相同、strip等价；异内容竞争201+409 |
| F 事务回滚 | F_rollback：真实PG trigger分别使历史/outbox/幂等写入失败，四表全部回滚；恢复后同键成功 |
| G 迁移 | G_emptyMigrationAndRepeat：独立空schema首次1次、重复0次，Flyway validate |
| H 破坏验证 | 本人过滤临时移除，实际断言失败，源码恢复后重新全量验证 |
| 最终格式 | finalFormatReflectsPersistedCreation：精确14/8字段集合、真实历史ID/actor/时间、null、版本、详情/原响应快照及审计内容 |
| 额外输入/键 | invalidKeysAndControlledFieldsLeaveNoData：非法键和更多受控字段不落数据；不同用户同键独立 |

真实数据库：嵌入式PostgreSQL17.11，随机本地端口，独立实例/测试schema，测试结束关闭；不是H2，不连接既有业务数据库。
外部替身：D authorization/resources由测试HTTP服务提供；真实HTTP客户端与服务凭据头检查。D真实角色、资源父级停用传播、TLS、网络超时仍未联调。
页面：本阶段没有M3页面，原Vue/D源码不改，不将历史页面测试计入本轮。

## 人工API验收

启动与curl模板见services/maintenance/README.md。真实D权限、服务密钥和同源入口评审具备后：
1. 用D真实会话及M3自己的CSRF，上报有效房间。确认201、PENDING_DISPATCH、报修人和院系真实、版本1、派单字段null、创建历史准确。
2. 重启服务或刷新查询后工单仍存在；对照M3四张表，无需访问D业务表。
3. 原键原内容重试响应一致；同键改描述409；并发重试只一单。
4. 用户B不能看到A详情（404），列表total也不能包含A；伪造reporterId参数400。
5. 无效房间/类型/受控字段不落数据；缺CSRF、无权限、失效会话、D不可用均不得完成受保护操作。
6. outbox仅本地待投递；不得宣称D已接收。正式数据库必须使用专用角色。

上述真实演示尚未执行。可先复跑自动测试，但测试用户/房间仅是替身，不用于伪装真实联调。

## 与原记录差异、待办及提交

原1A验收记录已备份至仓库外，只作历史来源。重建提前采用最终Format，增加WorkOrderHistory映射和2项测试；V1不变。尚未进入阶段的测试保留在安全提交，后续逐项恢复，不通过删除失败测试缩小验证。
D依赖见../requirements/m3-d-capability-requests.md；真实D联调、数据库最小权限部署和同伴schema复核仍待完成。
拟提交说明：feat(m3): [CF-M3-001][CF-M3-002] 按最终规则重建上报与本人查询后端。
精确文件以对应Git提交为准，阶段范围见m3-reconstruction-plan.md。不包含备份、日志、target、依赖缓存、秘密或其他模块业务变更。
人工验收：2026-11-03用户明确通过。阶段提交以包含本记录的Git提交为准；哈希在下一阶段记录及仓库外进度清单中登记，避免提交自引用。真实D联调仍未通过。
