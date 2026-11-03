# M3重建任务1：管理工单列表与详情验收包

2026-11-03。关联CF-M3-002。用户已确认重建顺序；1A提交0be6fc9d1ac62c857d4121bc8046919492d6eba7，1B提交2459c2af10371f71efc3e7923564e9fb81d6576d，均仅本地。
用户于2026-11-03回复“可以，继续做”，验收本阶段内部重建并授权本地提交及继续任务2；此结论不代替真实D联调或外部评审。

## 实现与文件

- Management.java/ManagementApi.java：GET /api/v1/maintenance/management/work-orders 与 GET /{id}。
- 每次通过D验证CF_SESSION及maintenance.read.scope allowed=true；ADMIN必须同时获业务权限才全局，否则只查身份中的departmentId。范围用于列表、count、详情，不信任客户端身份或院系参数。
- 列表page从0起、size默认20且1–100、page最多100000；按created_at/id倒序。支持无status或PENDING_DISPATCH，当前V1不支持其他业务状态，因此ASSIGNED等返回400，任务3才扩展。
- 列表描述最多120个PG字符，详情保留完整描述。列表/详情及total在REPEATABLE_READ查询。
- V1只有创建事实，assigneeId/dispatcherId/assignedAt为null，version=1，updatedAt=createdAt；无schema变更，原迁移字节不变。
- 管理响应按Format第7条裁剪：不返回reporterId、departmentId、history/actorId或无关个人资料。不是另建工单模型；完整本人详情仍按最终格式。历史授权接口和展示留任务4。
- ManagementView.vue及测试：列表、筛选、翻页、详情、加载/空态/可恢复错误；失效或越范围反馈、撤权和账号切换清除旧内容，旧异步响应不能覆盖新账号状态。
- App.vue/router.ts增加管理导航及/maintenance-management、/maintenance-management/:id，继续复用现有认证和M3客户端。
- 不引入候选、派单、维修、独立历史页面或通知；不修改D、既有CSRF、代理、依赖或业务数据库。

精确文件清单见 [本阶段文件](m3-rebuild-management-task1-files.txt)。

## 实际验证命令及结果

仓库根 D:/CampusFlow-Worktrees/m3-staged：

~~~powershell
$env:JAVA_HOME='D:/oracleJdk-27'
$env:TEMP='D:/CampusFlow-Tools/tmp'
$env:TMP=$env:TEMP
$mvn='C:/Users/fgrhy/.m2/wrapper/dists/apache-maven-3.9.11/03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc/bin/mvn.cmd'
& $mvn -o '-Dmaven.repo.local=D:/CampusFlow-Tools/m2/repository' '-DargLine=-Djava.io.tmpdir=D:/CampusFlow-Tools/tmp' -f services/maintenance/pom.xml verify
~~~

退出0，14 tests，0 failures/errors/skipped，BUILD SUCCESS。日志services/maintenance/target/rebuild-management-verify.log；Surefire报告在同级surefire-reports。

apps/web：

~~~powershell
node node_modules/vitest/vitest.mjs run
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
node node_modules/vite/bin/vite.js build
~~~

24项测试、6文件通过；类型检查退出0；构建退出0，65 modules。原D的7项测试保留并通过。日志在根tmp/rebuild-management-tests.log、rebuild-management-types.log、rebuild-management-build.log。
另运行 python scripts/check_repository.py 检查需求引用及布局；结果见文末检查记录。
所有日志与待验收源码快照另存仓库外备份rebuild-evidence/management-task1，不提交日志/依赖/构建产物。

## 独立验收映射

| 验收 | 实际测试与证据 |
| --- | --- |
| 1 仅授权范围 | managementScopePaginationAndPrivacy验证真实PG两院系工单，本院系list/total与获权全局结果；managementBothDepartmentsAndMinimalProjection验证另一院系上下文也只见其数据 |
| 2 普通人拒绝 | managementDeniedAndInvalidRequests：普通成员、无业务权限ADMIN、失效/无会话，列表和详情拒绝 |
| 3 越范围详情 | 院系身份直接GET其他院系ID返回404；伪造departmentId/actorId及重复参数400；获权全局身份可见 |
| 4 数据一致 | 状态筛选、两页不同ID、同时间稳定排序、空集合、非法分页/状态；total与真实PG种子记录一致 |
| 5 隐私 | 列表摘要120字符、详情完整；精确11字段白名单，无报修人/院系/历史身份/凭据字段；前端纯文本转义 |
| 其他边界 | 房间停用不阻止历史工单管理查询；D不可用拒绝；账号切换/撤权后的旧页面响应丢弃 |

真实PostgreSQL：独立嵌入式17.11，随机端口，真实SQL/事务/分页；既有1A并发幂等/回滚/格式/迁移测试本轮一并通过。
外部替身：D为HTTP替身，真实D角色/权限及授权范围语义仍待评审联调；前端API为测试替身。
真实浏览器人工验收与真实D端到端：未执行。不得以这些测试宣称已完成。

派单权限/并发的临时破坏验证仍留任务3；本阶段不伪造派单实现。1A已有的本人过滤破坏证据保留，本轮不冒充新破坏证据。

## 人工页面与API步骤

前置：D确认read.scope角色/范围、发放服务凭据，入口负责人确认并实施M3优先路由及独立CSRF；此前不打开VITE_M3_INTEGRATION_APPROVED。

1. 普通报修人登录，管理导航不可见；直接打开/maintenance-management应拒绝；直接管理GET应403。
2. 具有read.scope的院系用户打开列表，核对同院系工单、total、分页和待派单筛选，详情展示完整描述。
3. 请求另一院系工单ID应404；添加departmentId/actorId不扩大权限，应400。
4. 获权ADMIN可查全局；仅ADMIN但无业务权限仍应403。
5. 停止D后请求503且页面可重试；换账号/撤权后不能显示旧工单；恢复D后刷新。
6. 回归本人列表/详情和D登录、退出、通知。以上真实链路目前待执行。

~~~powershell
curl.exe -i -b $jar "$base/api/v1/maintenance/management/work-orders?page=0&size=20&status=PENDING_DISPATCH"
curl.exe -i -b $jar "$base/api/v1/maintenance/management/work-orders/$id"
~~~

$jar只能来自真实登录并妥善保护，不能打印/提交Cookie或服务密钥；$base为经评审的同源入口。

## 记录差异与外部待办

原任务1验收记录m3-management-task1-validation.md保留为历史来源；当时后端11/前端21项，不是本轮结果。当前在1A/1B新增测试基础上，再增加管理双向范围/格式白名单及页面旧响应测试，得到14/24项。
按最终Format预留创建事实字段；未派单字段null是本阶段真实状态，不冒充已指派人员。无V2/V3或旧迁移修改。
D-M3-01/02/05/06仍待；管理范围是M3内部已采用方案，不代填D评审。生产专用数据库角色/schema部署复核仍保留。
候选查询、最终资格复核、首次派单、历史接口和展示分别留后续阶段，不将未完成项标accepted。

## 拟提交及人工确认

拟提交：feat(m3): [CF-M3-002] 重建按授权范围隔离的管理查询。
本阶段验收：2026-11-03用户明确通过。提交以包含本记录的Git提交为准，哈希在下一阶段和仓库外记录；未推送。

## 最终检查记录

python scripts/check_repository.py：退出0，4 modules、18 requirements。验收前git diff --check通过、暂存区为空；验收后按清单暂存提交。原工作目录源码哈希未变；V1迁移、D代码、代理和依赖未变。检查不代替真实联调。
