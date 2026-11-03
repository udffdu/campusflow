# M3重建任务4：历史与交接验收包

2026-11-03。按最终Format规则重建，不伪造历史日期。用户“可以，继续做”已批准任务3，完成本地提交cc0b0f93a4cc81e7595fd40fde1004dcf3a0223b，并进入任务4。
任务4内部实现/验证完成，等待用户验收；尚未暂存/提交/推送。原历史验收记录仅为资料，本页为本轮实际证据。

## 需求、实现与文件

关联CF-M3-002（受权详情/历史）、CF-M3-003（首次派单历史原子性与重放）。需求基线保持proposed，未将外部待办自动accepted。
详细 [修改文件清单](m3-rebuild-history-task4-files.txt)。

| 文件/能力 | 本阶段实现与原因 |
| --- | --- |
| Api、ManagementApi | 新增本人和管理GET /{id}/history，复用详情查看权限，不提供编辑/删除接口 |
| Management | 通过工单范围查询后返回历史；详情含history，列表仍无history；保持reporterId/departmentId裁剪 |
| WorkOrderHistory组件、两种详情页 | 复用已存在的后端八字段历史，显示真实actor/assignee ID、前后状态和上海时间；纯文本、无修改按钮 |
| V3__format_v1_responses.sql | 原样恢复已保全迁移，兼容旧幂等响应；不修改V1/V2，不改业务事实与原始内容签名 |
| ReportingTest、页面测试 | 历史事实/重放/隔离/排序/禁止修改/迁移测试；新增失效授权和页面会话过期清除 |
| D清单、姓名契约、同学交接 | D缺失能力与M3适配工作分开；统一Format说明后续维修边界，无虚构D接口 |
| AGENTS、spec、README、重建计划 | 同步当前阶段与真实依赖，不把历史快照当当前状态 |

本人详情完整14字段，历史8字段，与 contracts/m3-work-order-format-v1.md（原Format.md一致）对齐。管理受权详情展示历史操作者ID，不暴露账号凭据或额外个人资料。历史排序occurred_at,id升序；查看工单与历史在可重复读事务中。没有普通用户历史修改入口，但未承诺数据库管理员不可更改。

本地历史来自M3事务，不是D审计回执。任务3投递器处理创建/派单审计，默认关闭；真实D未接入，不能宣称审计已送达。

## 实际命令和结果

根目录 D:/CampusFlow-Worktrees/m3-staged：

~~~powershell
$env:JAVA_HOME='D:/oracleJdk-27'
$env:TEMP='D:/CampusFlow-Tools/tmp'
$env:TMP=$env:TEMP
$mvn='C:/Users/fgrhy/.m2/wrapper/dists/apache-maven-3.9.11/03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc/bin/mvn.cmd'
& $mvn -o '-Dmaven.repo.local=D:/CampusFlow-Tools/m2/repository' '-DargLine=-Djava.io.tmpdir=D:/CampusFlow-Tools/tmp' -f services/maintenance/pom.xml verify
~~~

最终从空target执行以上命令：BUILD SUCCESS，31测试，0失败/错误/跳过（ReportingTest28、EligibilityGuardTest3）。真实临时PostgreSQL17.11、独立随机端口/schema；D身份HTTP替身、候选/资格/审计发送替身。未连接业务库，不等于真实D联调。

保留的失败证据：
1. 首轮verify共31项，4失败。复制原文件的旧时间戳使Maven提示Nothing to compile并沿用旧class：历史接口未注册、管理详情缺history。未删除或弱化断言。
2. 尝试离线clean verify，因缓存缺maven-clean-plugin:3.4.1失败，未新增依赖。
3. 核实target绝对路径在当前worktree内，将旧target整体移至仓库外 rebuild-evidence/history-task4-first-build；重新verify完整编译后31项通过。
日志分别为tmp/rebuild-history-first-failure.log、tmp/rebuild-history-clean-verify.log、tmp/rebuild-history-final-verify.log，旧失败Surefire报告也保留在外部旧target中。

apps/web目录：

~~~powershell
node node_modules/vitest/vitest.mjs run
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
node node_modules/vite/bin/vite.js build
~~~

36项/9文件通过；类型检查退出0；生产构建通过（71 modules）。日志tmp/rebuild-history-tests.log、tmp/rebuild-history-types.log、tmp/rebuild-history-build.log。包含D原有前端测试；未进行真实浏览器视觉验收或真实D回归。

仓库结构检查：python scripts/check_repository.py；空白检查：git diff --check。结果见收尾核对。

## 独立验收对应

| 项 | 实际证据 |
| --- | --- |
| 1 派单只追加一条 | historyMatchesFormatAndActualFacts：真实创建→资格拒绝仍1→成功派单共2条；并发同键测试仍通过 |
| 2 拒绝不记成功 | 资格拒绝保留创建历史；dispatchRollback注入PG写失败验证全事务回滚 |
| 3 字段准确 | 精确14/8字段集合、actor/from/to/assignee与操作一致，派单历史时间等于assignedAt；页面上海时间/纯文本测试 |
| 4 重试不重复 | 原键重放仍2条；并发同键/不同管理员竞争与创建快照重放测试继续通过 |
| 5 历史权限 | A/B本人越权404、普通用户管理403、越院系404；未登录/失效401、撤权403、D故障503、额外参数400 |
| 稳定排序与只读 | 同时刻按id排序；本人/管理POST/PUT/DELETE历史接口405且记录数不变 |
| V3迁移 | 空库3次迁移后重复0；V1升级2次；旧V2创建/派单响应升级1次，保留原业务快照与键；不改历史迁移 |
| 页面反馈 | 详情真实历史渲染、空态、加载/错误复用；会话过期清除旧历史、撤权不显示；账号切换旧响应保护保留 |

本轮没有再次临时破坏派单权限：任务3已实际验证expected403/actual200失败并恢复，证据见重建任务3验收包；本轮完整测试继续覆盖该关键断言。

## 人工页面/API验收步骤（待真实依赖到位）

先由D/M3/入口负责人确认权限、候选/资格HTTP契约、真实适配及代理/独立CSRF；未确认前保持默认关闭，不用fixture冒充真实人员。

1. 真实用户上报后打开本人详情，只显示CREATED；记下工单UUID。
2. 获权管理员进入管理列表，筛选/翻页后打开该工单；详情显示真实创建历史。
3. 搜索并选择真实合格维修人员、首次派单；刷新后ASSIGNED、人员/时间/版本正确，历史恰好CREATED+ASSIGNED。
4. 使用原幂等键重放，不新增历史；非法状态/资格/范围拒绝，不产生成功历史。
5. 对照M3数据库中的历史字段与页面：UTC时刻对应上海显示，actor为实际操作者，assignee为当时维修人员。
6. 用户B读A历史404、无管理权403、跨范围404。会话退出/失效后清除详情。
7. POST/PUT/DELETE历史接口405；审计故障时本地历史仍存在，不显示集中审计成功承诺。

~~~powershell
curl.exe -i -b $jar "$base/api/v1/maintenance/work-orders/$id/history"
curl.exe -i -b $jar "$base/api/v1/maintenance/management/work-orders/$id/history"
~~~

$base为已评审同源入口，$jar为真实登录Cookie私有文件，$id为真实工单；不打印/提交凭据。仅查询本模块数据库：

~~~sql
SELECT id,action,actor_id,from_status,status AS to_status,assignee_id,remark,occurred_at
FROM work_order_history WHERE work_order_id='工单UUID'
ORDER BY occurred_at,id;
~~~

## 差异、依赖及待提交

相比原历史记录24后端/28前端，本轮31/36，增加跨阶段权限/候选/账号切换测试。重建从1A已使用最终Format，任务4不重复改早期DTO。管理详情新增history后，相应字段集合断言增加history；仍明确禁止reporterId/departmentId/password/token，不弱化隐私边界。

V3升级会改变保存响应的序列化表示；业务键、内容签名和原创建/派单事实不变，不保证升级前后字节一致。迁移只在临时测试库验证，部署/schema复核仍待；不能用切换源码降级业务数据库。

D-M3-01～07仍待权限/人员接口/真实HTTP适配/环境/入口/审计联合验收；08姓名可选，当前显示ID。通知未实现。后续维修只有统一模型、版本/历史约定和适配边界，没有开始维修接口。详见 [D清单](../requirements/m3-d-capability-requests.md)、[维修交接](../requirements/m3-teammate-handoff.md)。

人工确认位置：本页首段记录用户批准任务3并进入任务4；重建总体方案见m3-reconstruction-plan.md。任务4本身等待验收。
拟提交：feat(m3): [CF-M3-002][CF-M3-003] 重建操作历史与Format交接。
本阶段不提交，待用户明确验收后再本地commit；不推送。第六提交哈希与最终恢复清单在验收提交后补齐。

## 收尾核对与保全成果对照

仓库检查通过：4模块、18需求；清理D文档3处Markdown行尾空格后git diff --check通过。暂存区为空；本阶段清单23文件。原工作区161个文件SHA-256全部未变，V1/V2/V3逐字节与保全版本一致，Format副本与原文一致。D服务、代理、公共会话客户端和依赖清单无改动。常见私钥/token模式检查无匹配，不等于完整秘密审计。

保全提交全部跟踪文件在重建工作区存在；原后端测试方法全部保留。与保全源码的差异：ReportView/DispatchPanel新增账号切换保护及对应测试；候选/管理/历史测试扩充；App导航次序不同但入口不缺失；管理历史显示位于派单面板之后；import顺序和EligibilityGuard注释更新。后端其他业务源码一致。文档/需求追踪按重建阶段更新，同学交接消除旧格式；原历史验收包原样保留。未通过字节对比冒充真实D联调。

忽略CRLF后的差异文件（新增重建验收文件另见清单）：

- AGENTS.md
- apps/web/README.md
- apps/web/src/App.vue
- apps/web/src/components/CandidatePicker.test.ts
- apps/web/src/components/DispatchPanel.test.ts
- apps/web/src/components/DispatchPanel.vue
- apps/web/src/components/MaintenanceViews.test.ts
- apps/web/src/components/ManagementView.test.ts
- apps/web/src/views/ManagementView.vue
- apps/web/src/views/MyReportsView.vue
- apps/web/src/views/ReportView.vue
- docs/requirements/m3-d-capability-requests.md
- docs/requirements/m3-teammate-handoff.md
- services/maintenance/README.md
- services/maintenance/src/main/java/io/campusflow/maintenance/EligibilityGuard.java
- services/maintenance/src/test/java/io/campusflow/maintenance/ReportingTest.java
- spec/m3-context.json
- spec/requirements.json

最终六阶段提交哈希及恢复清单在任务4验收提交后确认，本页是提交前对照。证据位于仓库外 D:/CampusFlow-Backups/m3-before-rebuild-20261103-152946/rebuild-evidence/history-task4。

## 人工验收结论

2026-11-03，用户回复“可以，继续”，批准任务4内部重建成果及本地提交。前文待验收/未提交为交验时快照；本次提交后六阶段完成，停止开发，最终提交哈希与恢复清单保存仓库外。真实D联调、外部评审和部署仍未完成，不因此标为通过。
