# D 补充与 M1 范围/插座规则验证

日期：2026-10-28。三项直接实施授权见 [负责人说明书](../handoffs/m1-integration.md)。AI本地验证完成；用户随后提供M1及前端独立复验成功输出，D同次测试由本地报告补证，范围见下方记录。本批据此分步保存为本地提交，没有推送、远程CI或部署。

## 本轮实现与实测

| 范围 | 实际结果 |
| --- | --- |
| D后端完整verify | 24测试，0失败/错误/跳过，BUILD SUCCESS；15:16:47+08:00完成，22.799秒 |
| M1完整verify | 86测试，0失败/错误/跳过，BUILD SUCCESS；15:17:16+08:00完成，3.751秒 |
| 前端测试 | 4个测试文件、11测试通过；包含新增资源范围/插座表单的4个用例 |
| 前端类型检查/构建 | vue-tsc与vite成功，未改依赖版本或lock文件 |
| 原D迁移 | V1/V2不变；原迁移历史断言从2改为实际3，新增V3升级/重跑行为验证 |

D原17测试继续运行，新增7测试；M1原80测试继续运行，新增4个权限/范围规则和2个真实PG查询用例。D/M1各用独立临时PG，未连接演示数据库。前端离线npm ci使用工作区缓存准备正式仓库依赖，没有修改演示副本。

外层日志：tmp/d-supplement-platform-verify.log、tmp/d-supplement-seats-verify.log、tmp/d-supplement-web-tests.log、tmp/d-supplement-web-build.log。日志未纳入Git，不将临时测试账号、密码、密钥作为交付凭据。

附加检查通过：仓库登记（4模块、27需求）、JSON授权/状态/证据路径、说明书链接、破坏脚本语法、外层复验脚本bash语法及git diff --check。原15条需求未改写；D V1/V2、M1迁移、依赖版本/lock、CI配置和M2/M3源码保持不变。当前CI原有任务会自动发现新增测试，远程CI未运行。

## 行为映射

- seatPermissionsRequireExplicitAssignmentAndApplyToExistingSessions：MEMBER/ADMIN默认无新增权限，显式附加SEAT_USER后三项允许，撤销后同会话立即拒绝；空间维护权限未扩大。
- seatRoleIsProtectedAndCustomRolesCanSelectRegisteredSeatPermissions：内置角色不可覆盖，自定义角色可只授本人历史，不能据此创建预约。
- roomScopeSurvivesLegacyUpdatesAndIsVisibleThroughInternalApi：范围/归属分离，旧更新及null保留CAMPUS，版本变更、旧版本409、外院系管理仍拒绝，内部接口返回实际范围。
- roomScopeCanBeChosenOnCreateAndRejectsWrongResourceTypes、legacyNullScopeIsConservativeAndDatabaseRejectsInvalidScopes：新建显式范围、未知值拒绝、非ROOM不能设置；旧写入null按DEPARTMENT读取，数据库约束阻止非法非空值。
- powerOutletMarkerBelongsToIndividualSeatsAndRoundTrips：标准标记座位读写正确，教室不能设置，取消标记保留其他属性。
- v2DatabaseUpgradesWithoutGrantingRolesOrGuessingOutletLabels：隔离schema中真实执行V1/V2，放入旧资源/用户，再升级V3；既有教室回填并换版本、旧用户角色和自由文本不变、重跑零迁移、旧字段插入仍可用。
- ReservationAccessPolicyTest：D目录/管理权限不授予预约资格，查询与创建权限分离，本院系边界与CAMPUS跨院系规则生效，不变造用户身份。
- 新增真实PG查询测试：精确POWER_OUTLET筛选仍遵守占用，旧中文/大小写近似文本不匹配；身份入口组合范围及基础停用，不绕过原有条件。
- ResourcesView.test.ts：表单读取/保存范围及版本、旧响应保守默认、插座增删不改变其他标签、成员只能看中文详情且无编辑按钮。测试的是实际Vue组件，API传输被模拟，不冒充真实跨服务浏览器验收。

## 破坏实验

脚本scripts/check_m1_core_mutations.py扩展了--module seats/platform，默认仍为seats。每项复制服务源码到临时目录，运行指定测试后删除，编译错误或测试执行错误不能当作有效检测。

| 模块 | 故意破坏 | 断言失败数 |
| --- | --- | --- |
| seats | 相邻端点误判重叠 | 3 |
| seats | 移除提前窗口上限 | 2 |
| seats | 移除事务锁 | 1 |
| seats | 移除开放条件 | 1 |
| seats | 移除占用座位过滤 | 1 |
| seats | 移除本人跨教室冲突 | 1 |
| seats | 移除精确插座筛选 | 1 |
| seats | 移除本院系范围检查 | 1 |
| seats | 移除预约动作权限检查 | 1 |
| platform | 旧更新重置预约范围 | 1 |
| platform | 允许教室使用座位插座标记 | 1 |
| platform | 取消SEAT_USER内置保护 | 1 |

全部12项实验出现指定断言失败、0执行错误，两模块原始Java源码SHA-256不变。外层汇总位于tmp/d-supplement-mutations-seats/summary.json和tmp/d-supplement-mutations-platform/summary.json；不是修改正式代码后保留故意失败状态。

## 用户独立复验记录

用户leafwhisper286提供2026-10-28的终端输出：

- M1：86测试，0失败、0错误、0跳过，BUILD SUCCESS；16:05:32+08:00完成，Maven耗时3.220秒。
- 前端：4个测试文件、11测试全部通过，16:05:32开始，耗时365毫秒；随后vue-tsc与Vite构建成功，58模块转换、构建耗时82毫秒。
- 用户消息未贴出D段日志。AI核对同次生成的`services/platform/target/surefire-reports/TEST-io.campusflow.platform.PlatformIntegrationTest.xml`：24测试，0失败/错误/跳过，测试组耗时19.813秒，文件更新时间为16:05:27+08:00，与后续M1/前端运行时序吻合。此项记为本地报告补证，不写成用户贴出了D日志，也不将测试组耗时冒充D的Maven总时长。

本次记录为人工运行后提供结果、辅以同次本地报告核对的构建/测试复验；不声称逐行源码审查、浏览器实际预约、独立M1/D网络链路、其他成员评审或完整M1验收。此前43/80测试的历史报告仍保留，不挪用为本批证据。

AI据此按D后端、前端、M1内部规则和文档四个范围保存本地提交。代码未在复验后继续修改，不无故要求重复运行同一组测试。实际提交编号在本记录和git log中保存，推送/PR评审/合并另外记录。

| 本地代码提交 | 对应范围 |
| --- | --- |
| 21ac46b | CF-M1-012：D权限/角色、预约范围、插座校验、V3迁移及真实PG测试 |
| 40f3ba0 | CF-M1-012：管理界面范围/插座控件、权限说明及前端测试 |
| 0322b01 | CF-M1-001/012：M1动作权限/院系范围/插座筛选及扩展破坏实验 |

说明书、契约和本复验记录随后保存为文档提交，完整编号以git log为准。

## 后续重复验证与学习

理解两个新增管理控件的含义：管理归属和预约开放范围分开；“有插座”描述具体座位。角色分配仍须管理员显式操作，不因新增目录就给所有用户开通预约。

在任意终端目录运行：

```sh
bash "/Users/lily/Documents/Codex/2026-10-22/qin/scripts/check-d-supplement.sh"
```

按顺序预期D为24测试、M1为86测试（均0失败/错误/跳过及BUILD SUCCESS）、前端11测试通过，最后构建成功。脚本只测试/构建，不更新正在运行的演示。本批已取得上方复验证据；后续只在代码变更或新问题出现时按需重复。
