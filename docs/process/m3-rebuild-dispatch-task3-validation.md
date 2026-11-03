# M3重建任务3：首次派单验收包

2026-11-03。关联CF-M3-003。前置任务2经用户验收提交a82b723a97c24ce814171192d4eca9382033bf7e，仅本地。
本阶段内部实现/验证完成，等待用户验收，未暂存、未提交、未推送。任务4独立历史API和页面尚未重建。

## 实现与关键设计

- Dispatch/DispatchApi：POST /api/v1/maintenance/management/work-orders/{id}/dispatch，仅接收assigneeId、expectedVersion，要求Idempotency-Key及独立M3 CSRF。
- D实时验证CF_SESSION与maintenance.dispatch allowed=true，操作者来自会话；按真实工单院系校验范围。新派单仅PENDING_DISPATCH且版本匹配。
- AssigneeEligibility/EligibilityGuard：提交时独立复核人员启用/资格/服务范围；响应allowed必须明确true且assigneeId、roomId、departmentId完全匹配。生产PendingAssigneeEligibility默认503/D_ELIGIBILITY_NOT_READY，不依赖候选选择结果放行。
- 同用户同键同内容重放原结果；同键不同内容409。两管理员用同版本竞争，条件UPDATE只允许一个成功；后到者409，不覆盖已派单结果。
- 同一本地事务保存工单ASSIGNED、真实派单人/维修人/时间、version+1、恰好一条派单历史、审计outbox和幂等结果。失败全部回滚，不调用M1。
- V2按保全文件原样引入，V1不改；新增真实派单字段、历史状态字段、dispatch_results及审计重试状态。Orders/Management读取V2真实值；已有上报幂等结果仍重放创建快照。
- 本人详情历史按最终Format映射真实CREATED/ASSIGNED；管理详情与派单响应延续受权裁剪，不含reporterId/departmentId/history，完整历史查询和页面留任务4。无V3提前引入。
- DispatchPanel：选人后确认、连点防护、原键/人员/版本重试、409要求刷新、成功刷新工单。切换账号/撤权/工单版本或卸载后丢弃旧响应，避免旧请求触发新账号页面刷新。
- 查询筛选允许ASSIGNED；候选接口对已派单状态仍拒绝。没有改派、取消、开始维修、超时、完工或通知发送。
- 文件列表见 [本阶段清单](m3-rebuild-dispatch-task3-files.txt)。

## 审计投递与恢复

AuditDelivery/AuditTransport/DAuditTransport/AuditSchedule复用D现有POST /internal/v1/audit。默认M3_AUDIT_ENABLED=false；外部确认后启用。凭据只来自环境变量。

投递器读取同一audit_outbox，包含MAINTENANCE.REPORTED创建事件及MAINTENANCE.ASSIGNED派单事件；并非只消费派单。每批最多10条，SKIP LOCKED；成功DELIVERED，失败RETRY并记录attempts、last_error_code、next_attempt_at，指数退避最高间隔1小时，持续重试不静默丢弃。认证/载荷错误也需先修复根因，不能改eventId绕过去重。

默认调度间隔5秒（maintenance.audit-delay-ms可配）。发送事务与派单业务事务分开；D审计故障不撤销已提交派单，资格复核故障则不允许派单。D返回200才记投递成功；不代表通知已读。外部请求与本地写库无分布式原子性，稳定eventId和D去重用于处理超时/重发。

~~~sql
SELECT event_id,work_order_id,status,attempts,last_error_code,next_attempt_at,delivered_at
FROM audit_outbox ORDER BY created_at,event_id;
~~~

修复D/凭据后等待next_attempt_at自动重试；无未经授权的公网重试接口。真实D接收/去重仍未验证，本地历史也不等于集中审计送达。

## 实际命令与结果

仓库根 D:/CampusFlow-Worktrees/m3-staged：

~~~powershell
$env:JAVA_HOME='D:/oracleJdk-27'
$env:TEMP='D:/CampusFlow-Tools/tmp'
$env:TMP=$env:TEMP
$mvn='C:/Users/fgrhy/.m2/wrapper/dists/apache-maven-3.9.11/03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc/bin/mvn.cmd'
& $mvn -o '-Dmaven.repo.local=D:/CampusFlow-Tools/m2/repository' '-DargLine=-Djava.io.tmpdir=D:/CampusFlow-Tools/tmp' -f services/maintenance/pom.xml verify
~~~

初次及破坏恢复后的完整verify均退出0：27 tests（ReportingTest 24 + EligibilityGuardTest 3），0 failures/errors/skipped，BUILD SUCCESS。
日志services/maintenance/target/rebuild-dispatch-initial.log、rebuild-dispatch-final.log；报告surefire-reports。

权限破坏：仅临时把DispatchApi的maintenance.dispatch替换为maintenance.read.own，使用同样Maven参数附：
~~~powershell
-Dtest=ReportingTest#dispatchPermissionScopeAndQualification test
~~~
实际退出1，1 test，1 failure，0 errors；Status expected:<403> but was:<200>。finally按字节恢复DispatchApi后完整verify通过。日志rebuild-dispatch-mutation.log。没有删除失败测试或弱化断言，不提交破坏版本。

apps/web：
~~~powershell
node node_modules/vitest/vitest.mjs run
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
node node_modules/vite/bin/vite.js build
~~~
32项、8文件通过，类型检查退出0，生产构建退出0，69 modules。日志根tmp/rebuild-dispatch-tests.log、rebuild-dispatch-types.log、rebuild-dispatch-build.log。原D7项前端测试继续通过。
日志及待验收源码快照保存到仓库外rebuild-evidence/dispatch-task3，不进入Git。

## 独立验收映射

| 验收 | 实际测试及结果 |
| --- | --- |
| 1 合法派单持久化 | dispatchPersistsReplaysAndAuditRecovers核对真实PG人员、派单人、版本2、状态及管理刷新；dispatchFromRealReportPreservesFormatAndOriginalCreationReplay从真实上报到派单、本人详情，核对两条真实历史与时间 |
| 2 拒绝越权/范围/资格/状态 | dispatchPermissionScopeAndQualification、资格guard三测试；403/404/409/503，失败无成功历史/outbox/幂等残留；撤权后原键重放也403 |
| 3 并发仅一个成功 | dispatchConcurrentOnlyOneWinner：两个真实请求、不同操作者争同版本，200+409，历史/事件/幂等各一 |
| 4 重试无重复 | dispatchConcurrentSameKey四并发同键只有一份原响应；不同内容409；成功后新派单409；原上报重试仍返回创建快照 |
| 5 原子回滚 | dispatchRollback在历史INSERT/outbox INSERT/幂等UPDATE注入真实PG异常，version仍1且无部分提交，恢复后同键成功 |
| 6 审计失败可恢复 | 发送替身失败RETRY/原因可查，恢复DELIVERED，attempts=2且eventId不变；真实D发送与去重待联调 |
| 迁移 | 空库2迁移、重复0变更；带历史工单V1升级V2仅执行1次，version/updatedAt正确，validate通过 |

真实数据库：独立临时PostgreSQL17.11，真实SQL/事务/并发/迁移。
外部替身：D身份HTTP替身；候选/资格/审计发送接口替身。不是实际D目录或实际集中审计。
前端：组件/API替身，验证防连点、失败原键原内容、冲突刷新、账号切换旧响应丢弃；未进行真实浏览器视觉人工验收。
真实D链路：未完成，不能宣称整体通过。

## 人工页面/API步骤

前置：D确认业务权限、候选和最终资格HTTP契约并由M3接入；入口/CSRF获批且正确配置。未具备时不打开集成开关，不以测试provider演示真实派单。

1. 真实报修创建工单，获权管理员进入管理详情，选真实维修人员并派单；刷新后ASSIGNED、人员、派单时间、版本2正确。
2. 用M3库查询工单、历史、outbox及dispatch_results，确认同一成功事实；上报已有CREATED、派单追加一条ASSIGNED。
3. 原键原内容重试不增加记录；同键换人409；两个管理员旧版本竞争只有一个成功。
4. 普通用户403、跨院系404、资格变化/非法状态409、D资格不可用503；无成功派单残留。
5. D审计断开时派单可完成，outbox失败可查；恢复并启用worker后自动重试，真实D验证去重。
6. 回归本人/管理查询和D登录通知；独立历史页留任务4。

接口：POST /api/v1/maintenance/management/work-orders/{id}/dispatch。
JSON仅 {"assigneeId":"真实人员UUID","expectedVersion":1}；先GET M3 /csrf，发送X-M3-XSRF-TOKEN、Idempotency-Key及真实Cookie jar。不要打印/提交秘密。

## 与原记录差异、外部依赖及提交

原task3计划/验收记录保留为历史资料，不代替本轮结果。重建从第一阶段即按最终Format，故本阶段本人详情已提供正确八字段历史；管理权限裁剪及独立历史展示留任务4。
在既有测试基础新增真实上报→派单的Format/创建快照/撤权重放测试，前端增加账号切换响应保护；状态筛选测试将ASSIGNED改为成功空集合，因为V2现已支持该状态，并非弱化校验。
V1/V2与保全版本完全一致。重建只使用隔离测试库，不接生产库、不假设checkout可以降级数据库；同伴/schema部署复核仍待完成。
D-M3-01/02/03/04/05/06/07均保留未完成项；生产资格默认拒绝；资格检查到本地提交间的撤权竞态需D共同确认，没有跨服务锁承诺。
通知收件人未定，不生成通知。重试状态仅页面内存，离开/刷新后先查派单事实；首次派单状态/版本约束仍禁止二次覆盖。

拟提交：feat(m3): [CF-M3-003] 重建首次派单与事务幂等及审计恢复。
人工验收：待用户明确通过。阶段提交：尚无。后续任务4未开始。

## 收尾核对

仓库检查通过（4模块、18需求）；git diff --check通过。原成果161个源码文件逐一SHA-256比对不变；V1/V2逐字节与保全版本相同，无V3。D服务、原会话客户端、代理及依赖清单无修改。暂存区为空，任务3尚未提交。对本阶段文件执行常见私钥/访问令牌模式检查，未发现匹配；此检查不等于完整秘密审计。

人工验收记录：2026-11-03，用户回复“可以，继续做”，批准本阶段本地提交并进入任务4；真实D联调和外部评审待办不因此变为通过。
