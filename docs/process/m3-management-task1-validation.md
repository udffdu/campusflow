# M3 管理查询任务 1 验收包

2026-11-02。用户在本聊天确认“先按你的建议开始开发，同时维护一个需要D补充功能的文档”；本文件记录 AI 方案确认及实际工作。仅任务 1，未开始任务 2–4。需求 CF-M3-002，基线仍 proposed，当前等待人工验收。

## 实现和关键设计

- 新增 Management.java、ManagementApi.java：GET /api/v1/maintenance/management/work-orders?page=0&size=20&status=PENDING_DISPATCH；GET 同前缀 /{id}。status 可省略，当前只允许数据库实际支持的 PENDING_DISPATCH，未知值 400。
- 调用现有 D authorization，权限 maintenance.read.scope 且 allowed=true；ADMIN+已获业务权限全局，其他已获权限用户仅 identity.departmentId。当前 D 不含此权限，因此真实环境会拒绝，不借用公共权限。
- 院系约束应用于列表、count、详情 SQL；列表/total 使用 REPEATABLE_READ。分页0起，默认20，size 1–100；created_at/id 倒序。额外/重复参数拒绝；越范围详情404。
- 返回 id、roomId、faultType、description、status、createdAt、assigneeId。列表 description 最多120个数据库字符，详情完整；无 reporterId、联系方式、凭据或历史 actor。assigneeId=null，因为当前数据库只能保存待派单事实。任务3扩展模型后需同步映射，不能长期硬编码 null。
- 新增 ManagementView.vue、ManagementView.test.ts；修改 router.ts/App.vue 添加 /maintenance-management 与 /maintenance-management/:id，导航/路由检查业务权限。复用 PageState、Pagination、现有 M3 客户端，未另建认证。
- 管理页加载/失败/空态、状态筛选、翻页、详情、权限不足提示；切换用户或撤销前端权限会清除旧内容。房间显示实际 ID；未调用当前禁止停用资源的 DClient.room 作为历史查询前置条件。
- 无 schema 变更、无新增依赖、无 D 修改。代理及 CSRF 配置不变，未评审接入开关保持关闭。

## 实际验证

仓库根目录的 PowerShell 命令：

```powershell
$env:JAVA_HOME='D:/oracleJdk-27'
$env:TEMP='D:/CampusFlow-Tools/tmp'
$env:TMP=$env:TEMP
$mvn='C:/Users/fgrhy/.m2/wrapper/dists/apache-maven-3.9.11/03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc/bin/mvn.cmd'
& $mvn -o '-Dmaven.repo.local=D:/CampusFlow-Tools/m2/repository' '-DargLine=-Djava.io.tmpdir=D:/CampusFlow-Tools/tmp' -f services/maintenance/pom.xml verify
```

实际退出0，11 tests，0 failures/errors/skipped，BUILD SUCCESS。原8项加新增3项：managementScopePaginationAndPrivacy、managementDeniedAndInvalidRequests、managementEmptyAndStableOrder。真实嵌入式 PostgreSQL，D 为真实 HTTP 替身；数据库查询和业务范围没有 mock。日志 services/maintenance/target/management-verify.log，报告 target/surefire-reports。

在 apps/web 执行：

```powershell
node node_modules/vitest/vitest.mjs run
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
node node_modules/vite/bin/vite.js build
```

21项测试（原17+管理页4项）通过，类型检查通过，生产构建成功（65 modules）。首次受限环境因英文新目录写权限 EPERM 失败，授权后重跑全部通过；未将首次失败隐藏或计为通过。

| 验收 | 实际结果 |
| --- | --- |
| 1 授权范围 | 两院系真实SQL数据，院系管理列表/total只含本院系，授权全局账号包含全部，通过 |
| 2 普通用户拒绝 | 普通成员、无业务权限ADMIN、失效/缺失会话列表和详情拒绝，通过 |
| 3 越范围详情 | 院系管理请求他院详情404，全局获权者可访问，通过 |
| 4 分页/筛选/数据库一致 | status筛选、总数、第二页不同ID、同时间ID稳定排序、空结果、非法/重复参数通过；当前只有待派单状态，其他业务状态不伪造 |
| 5 最少数据 | 响应无reporterId/actorId/departmentId/history，详情保留必要故障文字，页面纯文本转义，通过 |

前端测试是客户端替身，不能证明真实登录授权；生产数据库专用角色/真实D/浏览器视觉验收均未完成。此前要求的“破坏派单权限或并发控制”安排在任务3（本轮没有派单实现可破坏），仍保留为必须完成项。

## 页面/API 人工验收

先由 D 完成清单 D-M3-01/02/05，并完成入口评审 D-M3-06；未具备前不打开前端接入开关或伪造权限演示。

1. 用普通报修用户登录，管理导航不可见；直接访问 /maintenance-management 应拒绝。直接GET管理API亦应403。
2. 用院系管理用户登录；打开管理列表，核对数据库同院系工单编号、房间ID、摘要、时间及总数，筛选待派单、翻页、刷新。
3. 访问本院系详情；猜测他院系工单ID应404。带 departmentId/actorId 等额外参数应400，不扩大范围。
4. 用获业务权限ADMIN查看全部；只有ADMIN角色但无业务权限应403。
5. 模拟D不可用，管理请求应503；恢复后重新加载。失效会话应进入原登录流程。
6. 检查响应不含无关个人字段，并回归本人查询及 D 登录/通知。

API 示例（Cookie jar由真实登录获得，不打印Cookie）：

```powershell
curl.exe -i -b $jar "$base/api/v1/maintenance/management/work-orders?page=0&size=20&status=PENDING_DISPATCH"
curl.exe -i -b $jar "$base/api/v1/maintenance/management/work-orders/$id"
```

上述真实人工链路未执行，不声明整体验收通过。

## 未完成、关联记录与建议提交

D 能力及负责人评审依赖见 ../requirements/m3-d-capability-requests.md。任务1不解决人员查询、首次派单、投递恢复或历史扩展。每个后续任务须本任务人工验收后明确开始。

建议提交说明（未执行）：feat(m3): [CF-M3-002] 增加按授权范围查询的管理列表与详情。
