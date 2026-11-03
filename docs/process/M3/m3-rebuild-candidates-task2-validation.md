# M3重建任务2：维修人员选择验收包

2026-11-03。关联CF-M3-003的候选选择部分。
前置任务1已由用户验收并本地提交283bd10ee73752649c6fab3069af6e19361b9742；用户于2026-11-03回复“可以，继续做”，明确验收本阶段内部重建并授权本地提交及继续任务3。真实D联调仍未完成，未推送。

## 实现、文件与边界

- CandidateProvider：search(actorId,roomId,departmentId,query,page,size)，返回类型化候选及items/page/size/total，具体字段见 [D适配约定](../../../contracts/m3-candidate-port.md)。
- PendingCandidateProvider：生产默认明确503/D_CANDIDATES_NOT_READY，不用假数据或空集合掩盖外部未接入。
- CandidatesApi：GET /api/v1/maintenance/management/work-orders/{id}/candidates?q=&page=&size=。D验证maintenance.dispatch allowed=true；真实操作者来自会话，房间/院系来自M3工单，服务端检查工单范围及PENDING_DISPATCH。
- page从0起、最大100000，size为1–100、默认20，q最多100码点并strip；拒绝自报actorId/departmentId及重复/未知参数。
- 校验响应页码、大小、total及实际条数；重复ID、停用、无维修资格、越服务范围等整页拒绝，不静默过滤导致total失真。对外只允许类型化字段，不透传D任意JSON。
- CandidatePicker.vue：搜索、翻页、显示名称/启用/维修资格/服务范围、单选事件；加载、空态、服务未接入及重试；换页/搜索/账号/工单/权限变化清除旧选择并丢弃过时响应。
- ManagementView详情只在待派单且有maintenance.dispatch时嵌入选择器。仅发出选择事件，无派单提交按钮或写请求；候选不是最终资格承诺。
- 无迁移、无新增依赖、无D代码或代理变更。工单继续沿用最终Format及现有V1，不扩展维修、通知、历史。
- 阶段范围见 [重建记录](m3-reconstruction-plan.md)，精确文件可用对应提交的git show --stat查看。

## 实际命令与结果

仓库根 D:/CampusFlow-Worktrees/m3-staged：

~~~powershell
$env:JAVA_HOME='D:/oracleJdk-27'
$env:TEMP='D:/CampusFlow-Tools/tmp'
$env:TMP=$env:TEMP
$mvn='C:/Users/fgrhy/.m2/wrapper/dists/apache-maven-3.9.11/03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc/bin/mvn.cmd'
& $mvn -o '-Dmaven.repo.local=D:/CampusFlow-Tools/m2/repository' '-DargLine=-Djava.io.tmpdir=D:/CampusFlow-Tools/tmp' -f services/maintenance/pom.xml verify
~~~

退出0，17 tests，0 failures/errors/skipped，BUILD SUCCESS。日志services/maintenance/target/rebuild-candidates-verify.log及surefire-reports。既有上报/查询/事务/迁移测试均保留运行。

apps/web：

~~~powershell
node node_modules/vitest/vitest.mjs run
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
node node_modules/vite/bin/vite.js build
~~~

29项、7个测试文件通过，类型检查退出0，生产构建退出0、67 modules；原D的7项测试仍通过。
日志根目录tmp/rebuild-candidates-tests.log、rebuild-candidates-types.log、rebuild-candidates-build.log。
证据与本阶段待验收源码快照存仓库外备份rebuild-evidence/candidates-task2；不提交日志、缓存、构建产物或备份。

## 独立验收与证据边界

| 验收 | 本轮证据与结果 |
| --- | --- |
| 1 真实合格候选显示 | 尚未完成，D真实候选接口及HTTP适配器仍缺；自动测试候选仅为测试数据 |
| 2 停用/无资格/越范围不得成为有效候选 | candidatesScopeEligibilityAndPagination验证服务端整页拒绝；普通用户拒绝、越院系工单404；页面禁选停用/无资格人员 |
| 3 接口失败可恢复 | 默认503明确未接入、D故障不放行；页面可重试，不伪造人员 |
| 4 无候选明确提示 | 真空集合返回200/total0，页面显示无候选且无选择事件；本阶段无派单动作 |
| 5 最少字段 | candidatesTraverseAllPagesAndUseServerContext核对候选精确6字段；不存在密码、令牌等透传字段 |

新增行为证据：
- candidatesTraverseAllPagesAndUseServerContext：21人分20+1两页，ID并集21，搜索后total正确；确认传入适配口的actor/room/department来自服务端；工单数量不变，历史/outbox/幂等无新增。
- candidatesRejectMalformedPagesAndQueryOverrides：null、重复ID、错误total、错误page响应均503/D_INVALID_CANDIDATES；非法/重复参数400、仅管理查询权限403、D故障503。
- CandidatePicker两项补充测试：无效人员不可选；切换账号后旧请求不能重新展示候选，撤权后选择清空。
- 原三个候选组件测试保留：搜索/分页/选择及清除、失败重试/空态、无派单权限不查询。

真实数据库：独立嵌入式PostgreSQL17.11，真实SQL与范围判断，无业务数据库操作。
外部替身：D身份使用HTTP替身；CandidateProvider使用测试Bean。测试Bean没有打包到业务jar，生产默认拒绝。
真实D登录/候选/浏览器链路、真实人员启停传播：未完成。测试通过不能视为真实集成通过。
本阶段V1只允许待派单，非法后续工单状态的实际持久化/派单资格复核验证留任务3，不修改数据库约束制造演示。

## 人工页面/API步骤

当前未获D及入口评审时保持集成开关关闭，不篡改D权限演示。可先复跑自动测试与审查默认provider。

D权限、入口具备但候选适配未接通时：
1. 使用真实获权账号打开待派单管理详情；候选请求应显示“维修人员服务尚未接入”，不会出现虚构人员。
2. 只有管理查询权限者不显示选择器；直接候选API仍需maintenance.dispatch。
3. 跨院系工单请求应404，客户端自报actorId/departmentId应400。

D完成正式HTTP契约且M3接入后：
1. 搜索真实维修人员，翻页核对完整total和稳定顺序，选择后显示当前人员。
2. 切换搜索/分页/工单或账号，旧选择清除；撤权或停用/无资格/越范围人员不能成为有效候选。
3. D故障显示可恢复错误；真实无候选显示空态，无派单提交。
4. 检查响应只含约定字段，前端不持有服务密钥。

~~~powershell
curl.exe -i -b $jar "$base/api/v1/maintenance/management/work-orders/$id/candidates?q=&page=0&size=20"
~~~

使用真实登录Cookie jar，不打印/提交令牌。以上真实链路尚未执行。

## D待办、差异与提交

D-M3-01/03/05/06仍待：业务权限与维修资格映射、候选HTTP接口、凭据环境、入口/CSRF。M3已给出数据适配口，不虚构D路径；D交付后仍由M3实现真实HTTP适配器。
D-M3-04提交时最终资格复核留任务3；候选选中不等于之后一定合格。详细状态见 [D清单](../../requirements/m3-d-capability-requests.md)。
原任务2记录已备份至仓库外，保留为历史证据。本轮额外增加分页/响应完整性/上下文及页面过时响应测试，按最终规则重建，不伪造旧日期和结果。
派单事务、幂等、并发和权限破坏测试留任务3，不提前实现；独立历史展示留任务4。

拟提交：feat(m3): [CF-M3-003] 重建维修人员选择及D候选适配口。
人工验收：2026-11-03用户明确通过。提交以包含本记录的Git提交为准，哈希在下一阶段及仓库外登记；外部评审不因此完成。

## 最终检查

python scripts/check_repository.py：退出0，4 modules、18 requirements。验收前git diff --check通过、暂存区为空；验收后按清单提交。原工作目录哈希未变；迁移、D、代理和依赖未改。测试证据和待验收源码快照已保存到仓库外，不进入Git。
