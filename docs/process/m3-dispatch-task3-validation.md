# M3任务3：首次派单验收包

2026-11-03。用户明确要求“继续完成任务3，给D留下接口，其他按照要求完成，同时维护清单”，本轮据此实施此前方案的M3内部部分。未代填同伴/schema负责人或D外部评审通过，部署前仍需其审核。仅任务3，不进入任务4。

## 实现与文件

CF-M3-003：Dispatch/DispatchApi 首次派单；V2__dispatch.sql 新迁移；Orders/Management兼容新增字段；DispatchPanel及ManagementView页面；AuditTransport/DAuditTransport/AuditDelivery/AuditSchedule审计投递；ReportingTest/DispatchPanel.test.ts行为测试。D资格复核复用 AssigneeEligibility/EligibilityGuard，生产默认 PendingAssigneeEligibility 拒绝，未修改D。

POST /api/v1/maintenance/management/work-orders/{id}/dispatch，JSON {assigneeId,expectedVersion}，头 Idempotency-Key 和 X-M3-XSRF-TOKEN；服务端从CF_SESSION验证 maintenance.dispatch，使用真实工单院系校验范围。普通查询权限不可派单。
事务唯一键 actorId+派单幂等键，内容含工单ID/人员/expectedVersion；同键同内容重放原响应，不同内容409。新操作必须待派单、版本匹配，D资格复核必须明确allowed=true且绑定同人员/房间/院系；条件UPDATE确保并发仅一方成功。派单字段、version+1、历史、审计outbox、原响应同事务保存。没有改派、取消、维修动作或M1调用。

V1原文不改；V2新增工单派单字段和version/updatedAt，历史action/from_status/assignee_id/remark，dispatch_results及outbox重试字段。已有工单updated_at回填created_at，version=1。所有相关旧INSERT改为列名形式；状态只扩展ASSIGNED。当前任务不实现完整统一历史展示（任务4），保留旧status历史接口兼容。

派单页冻结人员/版本/键，连点只发送一次，失败重试原内容，409要求刷新重新选择，成功刷新工单。未确认请求仅保存页面内存，刷新/离开会丢失重试上下文，必须先查询结果；首次派单状态约束仍防止第二次覆盖。

## 审计恢复

只发送审计MAINTENANCE.ASSIGNED，不生成收件人未确认的通知。M3_AUDIT_ENABLED默认false；外部确认后设置true启用worker，5秒调度（maintenance.audit-delay-ms可配置）。使用D已存在 POST /internal/v1/audit，服务密钥仅环境变量。
业务提交后worker用SKIP LOCKED领取最多10条；成功DELIVERED，失败RETRY，保存attempts/last_error_code/next_attempt_at。重试间隔指数增长，最长1小时，持续重试不丢弃；恢复D后自动重试，稳定eventId供D去重。每批最坏可能等待多次3秒HTTP超时；worker事务与派单事务完全分离。认证/载荷错误也保留RETRY，需运维修复根因，不自动改载荷或换eventId。

查询恢复状态（M3库，只读）：
```sql
SELECT event_id,work_order_id,status,attempts,last_error_code,next_attempt_at,delivered_at
FROM audit_outbox ORDER BY created_at,event_id;
```
无需手工修改数据库：恢复D/凭据并启用worker后等待next_attempt_at；不提供未经授权的公网重试按钮。D返回200才记投递完成，不表示用户已读。

## 实际验证命令

仓库根PowerShell：
```powershell
$env:JAVA_HOME='D:/oracleJdk-27'
$env:TEMP='D:/CampusFlow-Tools/tmp'
$env:TMP=$env:TEMP
$mvn='C:/Users/fgrhy/.m2/wrapper/dists/apache-maven-3.9.11/03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc/bin/mvn.cmd'
& $mvn -o '-Dmaven.repo.local=D:/CampusFlow-Tools/m2/repository' '-DargLine=-Djava.io.tmpdir=D:/CampusFlow-Tools/tmp' -f services/maintenance/pom.xml verify
```
最终结果以 target/dispatch-verify.log 和 surefire-reports 为证。真实嵌入式PostgreSQL；D身份使用HTTP替身，资格复核及审计发送使用接口替身，未冒充真实D。

apps/web执行：node node_modules/vitest/vitest.mjs run；node node_modules/vue-tsc/bin/vue-tsc.js --noEmit；node node_modules/vite/bin/vite.js build。26测试通过，类型检查通过，生产构建成功（69 modules）。

| 验收 | 实际测试 |
| --- | --- |
| 合法持久化/刷新 | dispatchPersistsReplaysAndAuditRecovers 检查人员、派单人、version=2、ASSIGNED和查询结果 |
| 权限/范围/资格/状态 | dispatchPermissionScopeAndQualification 及重放测试验证403/404/409/503，无成功历史/幂等残留 |
| 并发 | dispatchConcurrentOnlyOneWinner 两个不同操作者/人员竞争，必须200+409且历史/事件/幂等各一；dispatchConcurrentSameKey 四请求仅一份响应/历史 |
| 重试 | 同键原响应完全一致，同键不同人员409；已派单新请求409 |
| 回滚 | dispatchRollback 分别历史INSERT、outbox INSERT、幂等UPDATE注入真实PG触发器异常，version仍1，历史/事件/幂等为0；解除注入同键可成功 |
| 审计恢复 | 发送失败RETRY及原因，恢复后DELIVERED、尝试次数2、eventId不变；此为发送替身测试，真实D去重待联调 |
| 迁移 | 空库2个迁移、重复0变更；upgradeExistingV1Data 验证带历史工单V1升级V2和再次运行安全 |

破坏验证：临时将 DispatchApi 的 maintenance.dispatch 改成 maintenance.read.own，运行同样Maven参数附 -Dtest=ReportingTest#dispatchPermissionScopeAndQualification test；普通用户原应403，破坏后200使断言失败。finally恢复源文件，再执行完整verify。日志 target/dispatch-mutation.log。未删除或弱化断言；状态筛选原来拒绝ASSIGNED的测试改为拒绝INVALID，因为ASSIGNED现在是合法业务状态。

## 人工验收步骤（真实D链路待完成）

1. 完成D权限/资格适配和入口评审；启用真实候选与最终资格复核。未完成时生产应明确拒绝，不能使用测试provider。
2. 真实登录有派单权限账号，打开待派单详情，选择维修人员，提交；记录id/key/version。刷新应看到ASSIGNED、真实人员和派单时间；SQL核对工单/历史/outbox。
3. 另一个管理员使用旧版本派单应409；普通用户直接请求403；越范围404；资格撤销拒绝，不产生派单历史。
4. 重试原请求应返回同一结果；更换人员但同键409。模拟响应丢失后原请求重试，不新建历史。
5. D审计暂时不可用时派单仍成功，outbox RETRY可见；恢复后自动DELIVERED。资格校验不可用时则不得派单。
6. 回归上报/本人查询、管理筛选和D登录通知。完整历史展示属于任务4。

API模板：先获取M3 /csrf，再POST上述路径，body为 {"assigneeId":"真实人员UUID","expectedVersion":1}；使用真实Cookie jar，禁止打印令牌或服务密钥。

## 未完成及建议提交

D资格HTTP适配/真实权限/角色映射/入口CSRF评审/真实审计投递未完成；所有外部约定在D清单保留。资格检查与事务提交间不是跨服务原子事务，竞态语义待D确认。通知未发送；生产最小权限及同伴schema复核待完成。此报告不是整体验收通过。
建议提交：feat(m3): [CF-M3-003] 实现首次派单及幂等事务与审计恢复。未提交或推送；完成任务3内部工作后等待人工验收，不进入任务4。
