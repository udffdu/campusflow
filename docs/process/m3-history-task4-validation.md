# 任务4操作历史与Format.md对齐验收包

2026-11-03。用户明确授权任务4并要求“前面的修改如果不符合Format.md也需要修改”。依据文件为仓库上级 project/Format.md，未改其原文；原文副本保存 contracts/m3-work-order-format-v1.md。范围为创建/首次派单历史与现有输出对齐，不实现维修、验收、评价、M1联动。

## 修改、格式及权限

- WorkOrderHistory.java：统一八字段DTO，id/action/actorId/fromStatus/toStatus/assigneeId/remark/occurredAt，null保留。DB既有status列映射toStatus，不修改V1/V2迁移。按occurred_at,id升序。
- Orders：本人完整详情及新创建响应字段严格对应Format；移除对外resourceVersion（内部仍保存房间版本），详情包含history，列表不含history。工单version仍是业务版本。
- Management：获权管理详情及新派单响应包含统一history；列表不带history，继续裁剪reporterId/departmentId，符合Format第7条对外权限裁剪，不将个人信息扩散到列表。
- 新增GET /api/v1/maintenance/work-orders/{id}/history、GET /api/v1/maintenance/management/work-orders/{id}/history，返回八字段数组。分别先验证maintenance.read.own和本人过滤、maintenance.read.scope和院系范围；复用详情查询，没有按historyId绕过工单授权的入口。非法额外参数400，越范围404，缺权限403。POST/PUT/DELETE均405，无客户端历史写入口。
- 本人/管理详情在可重复读事务内查询工单及历史，避免并发派单导致状态与历史快照不一致。创建/派单仍在原业务事务写历史。
- V3__format_v1_responses.sql：只转换旧幂等响应的表示，删除resourceVersion/旧history，关联真实历史行重建八字段；创建保留待派单/version1/null派单字段，派单保留原派单快照及截至assignedAt的历史。旧键、原始内容签名及工单事实不变；不是用当前详情替换原业务结果。格式升级前后的响应字节不承诺完全相同，升级后同键重放保持保存结果。
- WorkOrderHistory.vue：两种详情共用，显示操作、真实操作者ID、前后状态、当时维修人员、说明、上海时区时间；纯文本，无编辑/删除按钮。加载/错误/空态复用详情及组件；账号切换/撤权清除本人旧内容。历史存在不表示D审计已送达。
- maintenance.ts统一类型，状态展示覆盖Format五种状态及六种动作名称，仅为后续数据展示预留；数据库仍只支持当前已实现PENDING_DISPATCH/ASSIGNED，后续流程由同学另加迁移实现。

## 实际验证与命令

真实PostgreSQL：24项后端测试通过（含原派单/并发/回滚与资格测试），0失败/错误/跳过；D身份HTTP替身，资格/审计接口替身。不是D真实联调。

仓库根目录：
```powershell
$env:JAVA_HOME='D:/oracleJdk-27'
$env:TEMP='D:/CampusFlow-Tools/tmp'
$env:TMP=$env:TEMP
$mvn='C:/Users/fgrhy/.m2/wrapper/dists/apache-maven-3.9.11/03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc/bin/mvn.cmd'
& $mvn -o '-Dmaven.repo.local=D:/CampusFlow-Tools/m2/repository' '-DargLine=-Djava.io.tmpdir=D:/CampusFlow-Tools/tmp' -f services/maintenance/pom.xml verify
```
日志 services/maintenance/target/history-verify.log；Surefire报告位于同目录surefire-reports。

apps/web：
```powershell
node node_modules/vitest/vitest.mjs run
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
node node_modules/vite/bin/vite.js build
```
28项测试全部通过，类型检查通过，Vite生产构建成功（71 modules）。未做真实浏览器视觉人工验收。

| 验收 | 测试/结果 |
| --- | --- |
| 成功派单一条历史 | historyMatchesFormatAndActualFacts 从真实创建到派单，记录恰好2条，派单追加1条 |
| 拒绝不记成功 | 资格拒绝后仍只有创建历史；原dispatchRollback真实PG故障注入继续通过 |
| 字段准确 | 完整工单14个字段、历史8个字段精确集合，null/actor/from/to/assignee与真实操作一致，历史时间等于派单时间 |
| 重放不重复 | 同键重复派单仍2条；原多线程同键和不同管理员并发测试继续通过 |
| 权限隔离 | 他人本人历史404、普通用户管理历史403、院系越范围404；获权管理历史与本人历史一致；额外actorId参数400 |
| 稳定排序/禁止编辑 | 同一时间记录按id升序；POST/PUT/DELETE405，数据库记录数不变 |
| 升级与格式 | 带创建/派单两种旧响应的V2库升级V3，保留真实history id、创建原状态/version与派单原状态/version；重复迁移0变更；空库3迁移及V1升级继续通过 |
| 页面 | 动作/真实ID/状态迁移/上海时间显示、纯文本转义、无修改按钮、空态；既有详情失败清除/无权限测试继续通过 |

本轮未重复破坏派单权限；任务3已完成该变异验证及恢复，本轮完整测试包含其权限断言，不把历史测试当成新的破坏证据。

## 页面/API人工验收步骤

D权限/候选/资格及入口评审具备后：
1. 真实用户创建工单，打开本人详情，只有CREATED，fromStatus=null，actorId为报修人。
2. 获权管理员打开管理详情，选择真实维修人员派单，刷新显示CREATED+ASSIGNED；派单actorId为管理员、assigneeId为选中人员。
3. 用原key重复派单，history仍2条；非法状态/无资格/无权限派单不增加成功历史。
4. 对照M3 work_order_history查询id/action/actor_id/from_status/status/assignee_id/remark/occurred_at；核对八字段响应及上海时间展示。
5. 用户B访问A的本人history应404；普通用户管理history403；跨院系管理员404；通过详情URL也不能越权。
6. 尝试POST/PUT/DELETE历史接口，应405且数据不变。D审计发送失败时本地历史仍存在，页面不宣称集中审计已收到。

```powershell
curl.exe -i -b $jar "$base/api/v1/maintenance/work-orders/$id/history"
curl.exe -i -b $jar "$base/api/v1/maintenance/management/work-orders/$id/history"
```
只用真实登录Cookie jar，不打印Cookie/密钥。上面真实链路尚未执行，不能当整体验收通过。

## D待办与需求追踪

CF-M3-002：受权详情与历史查询；CF-M3-003：派单原子历史和幂等一致性。状态仍保留人工验收/真实联调待完成，不自动accepted。
D-M3-08给出 contracts/m3-history-identity-port.md：如需姓名，受权工单后批量解析最少名称，缺失/故障时显示ID，不改写历史。当前Format只要求ID，因此不伪造HTTP接口或加入无用网络依赖。D-M3-01/04/05/06/07依然未完成。

流程演示准备清单：管理列表→查看工单→选择维修人员→派单→刷新确认→查看操作历史。仅作为待真实联调的演示步骤；未宣称四任务已全部人工验收。
建议提交：feat(m3): [CF-M3-002][CF-M3-003] 按Format统一工单历史并补齐授权展示。
未提交/推送，任务4内部完成后停止，等待人工验收。
