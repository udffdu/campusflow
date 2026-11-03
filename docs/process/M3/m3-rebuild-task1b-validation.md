# M3重建任务1B验收包

日期：2026-11-03。关联CF-M3-001（报修页面）及CF-M3-002（本人查询页面）。
前置任务1A已由用户“可以，继续做”验收，本地提交0be6fc9d1ac62c857d4121bc8046919492d6eba7。用户于2026-11-03回复“可以，继续做”，确认本阶段内部验收并授权本地提交；真实D联调仍未完成，禁止push；管理查询等后续任务未开始。

## 实现、文件与规则

- ReportView.vue：房间UUID、故障类型、描述；复用session权限与UI样式。首次合法提交冻结body和UUID幂等键；连点不重复发送，网络失败保留原键/内容，明确400后可修改，成功“新建报修”生成新键。
- MyReportsView.vue：本人列表、分页、详情；复用PageState/Pagination；请求不携带reporterId/actorId/departmentId。详情时间按上海时区展示。加载、空态、404、失败及无权限有反馈。
- maintenance.ts：使用/api/v1/maintenance前缀、same-origin凭据；每次写请求先取M3 /csrf，仅接受X-M3-XSRF-TOKEN。D令牌不能替代；401触发现有session-expired事件。
- router.ts/App.vue：新增/maintenance/new、/maintenance、/maintenance/:id及权限导航；沿用现有认证路由guard，不新建登录。
- 保留最终Format类型；本阶段不引入独立历史组件/API、管理、候选或派单UI。后端已提供创建历史数据，完整历史展示留任务4。
- 账号变化/撤权清除旧详情、草稿、结果和重试上下文；响应序号隔离过时请求。页面内存中的失败重试键不会跨刷新持久化，离开/刷新后必须先查本人记录，不能声称恢复原提交。
- 浏览器校验仅改善体验，真实身份、受控字段、房间有效性与事务仍由任务1A后端校验。

阶段范围见 [重建记录](m3-reconstruction-plan.md)，精确文件以对应Git提交为准。此阶段不修改package.json/package-lock.json或引入新依赖；使用既有固定版本依赖的独立副本，不共享原目录的可写node_modules。

## 1. 页面测试与生产构建：通过

工作目录 D:/CampusFlow-Worktrees/m3-staged/apps/web；Node v24.22.1。

~~~powershell
node node_modules/vitest/vitest.mjs run
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
node node_modules/vite/bin/vite.js build
~~~

实际结果：5个测试文件、19项测试通过，0失败；类型检查退出0；生产构建退出0，63 modules。
日志在仓库根tmp/rebuild-1b-tests.log、tmp/rebuild-1b-types.log、tmp/rebuild-1b-build.log，另复制到仓库外备份的rebuild-evidence/task1b。日志/缓存/dist不提交。

首次启动测试时日志目录不存在，PowerShell重定向失败，测试未执行；创建被忽略的tmp目录后上述检查均实际执行通过。该准备失败不计作测试结果。

覆盖：
- M3接入开关关闭时fetch完全不调用；
- M3路径、独立CSRF、same-origin、稳定幂等键、拒绝D CSRF；
- 会话过期事件、网络错误、权限不足；
- 连点、相同请求重试、成功后的新键、400后可修正；
- 加载/空态/分页/详情404清除、文本转义；
- 账号切换时旧报修响应丢弃并生成新用户的新提交键；
- 账号切换/撤权后旧详情响应不能覆盖当前错误或权限状态。

本轮保留并运行D原有api/session/通知组件7项测试；M3客户端4项、页面8项。全部为前端自动测试，fetch/API为替身，不代表真实登录或真实数据库联调。未执行真实浏览器视觉人工验收。

## 2. M3后端接入：契约对齐，真实网络链路未完成

客户端路由、body字段、CSRF和幂等头与已验收1A一致，详情类型按最终Format。后端、V1迁移均未改，本轮不重复计入1A的10项PG测试结果。

当前Vite/Nginx代理没有启用M3规则；VITE_M3_INTEGRATION_APPROVED默认关闭，防止请求误进D。测试中开启该值仅用于验证请求构造，不是部署许可。不能只设置true而不落实代理。

## 3. 真实D登录授权：未完成

D业务权限/角色、真实联调凭据、共享入口及独立CSRF评审仍未完成。不修改D，不借用spaces.write、不用测试身份演示真实接入。真实用户提交/刷新、多账号隔离、失效会话和D登录/通知回归仍待联合验收。

待评审方案：
- 同源/api/v1/maintenance/优先到M3:8083，完整路径和Cookie保留；其他/api/仍到D:8080。
- M3使用M3-XSRF-TOKEN Cookie和X-M3-XSRF-TOKEN头；D的api.ts和CSRF逻辑不改。
- /internal/不能公开到浏览器入口；生产HTTPS、Secure、SameSite/Path由相关负责人确认。

D清单见 [阶段依赖](../../requirements/m3-d-capability-requests.md)。外部评审未因本地页面通过而改变状态。

## 独立验收与人工步骤

| 原验收项 | 本阶段结论 |
| --- | --- |
| 1 真实登录提交并刷新查询 | 待D/入口具备，未执行 |
| 2 本人列表分页详情隔离 | 页面行为测试通过；服务端隔离见1A，真实多账号链路待验 |
| 3 校验、空态、加载、网络/会话反馈 | 自动测试通过；视觉人工检查待验 |
| 4 连点和失败重试 | 页面稳定键/内容与重复点击测试通过，刷新限制见上 |
| 5 无权限不可操作，绕过页面后端拒绝 | 页面测试通过；后端拒绝证据来自1A，非本轮真实联调 |
| 6 D功能不退化 | 原7项测试通过，D客户端/代理无变更；真实回归待验 |
| 7 测试、类型、构建及真实链路 | 前三项通过；真实链路未完成 |

当前可复跑上述测试，审核默认关闭开关及权限guard。后续负责人确认并配置真实入口/权限后：
1. 登录拥有report/read.own的真实账号，打开/maintenance/new，填写有效房间UUID、类型及描述。
2. 提交后记录工单ID；打开/maintenance和/maintenance/{id}，刷新仍可查到，核对PENDING_DISPATCH与内容。
3. 分页检查total；更换真实账号B，猜测A详情应404且列表不含A；撤销权限/退出后不能继续查询。
4. 连续点击只创建一单；网络结果未知时原提交重试，核对数据库没有重复；成功新报修使用新键。
5. 验证无效输入、空列表、网络失败、会话过期；不通过改D权限或开关绕过未评审流程。
6. 回归D登录、退出和通知读取/已读。

本阶段不含管理工单页面或派单操作；无需验收候选数据。API人工模板另见services/maintenance/README.md。

## 与原记录差异及拟提交

原1B开发记录已备份至仓库外，属于历史来源，不能取代本次结果。当前前置1A已有明确验收提交；详情类型直接采用最终Format。相比原1B的17项测试，本轮增加2项账号切换/过时响应保护测试，共19项。任务4的历史组件未提前复制。

拟提交：feat(m3): [CF-M3-001][CF-M3-002] 重建报修与本人查询页面。
人工验收：2026-11-03用户明确通过；本记录所在提交即1B提交，哈希将在下一阶段及仓库外进度登记。此确认不替代真实D联调及入口评审。
