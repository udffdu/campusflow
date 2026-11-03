# B 首次 Review 交验记录

2026-11-03；基线 A 的 8a5fdb2，当前本地 feat/CF-M3-002-repair-lifecycle。开发方案：docs/requirements/m3-b-first-review.md；接口：contracts/m3-repair-api.md。这是本地交验材料，人工验收、外部评审及远程 CI 未完成。

## 已实现

- 被派维修人员本人列表/详情，独立于报修人和院系管理查询。
- 开始维修、提交完工、验收通过、驳回返修；同版本条件更新、动作幂等重放。
- 事务内保存真实历史、审计、M1 待同步意图；响应和页面不冒充 M1 已确认。
- 我的维修任务页面、处理面板和报修详情验收面板；旧管理筛选支持新状态。
- V4 新增迁移（待双方评审），V1/V2/V3 保持原样；CI 新增 M3 后端 job 并加入汇总门禁。

## 验证环境与命令

本机 Windows、Java 25（编译目标 21）、Node 24。后端使用真实临时 PostgreSQL 17.11；D 身份/资源/资格仍为测试替身，HTTP 由 MockMvc 驱动，不能称真实整套部署。

仓库根执行：

```powershell
  ./services/maintenance/mvnw.cmd -f services/maintenance/pom.xml verify
npm --prefix apps/web ci
npm --prefix apps/web test
npm --prefix apps/web run build
python scripts/check_repository.py
```

测试演示完整链路（服务目录运行）：

```powershell
./mvnw.cmd '-Dtest=ReportingTest#lifecycleFullChainAndOriginalReplay' test
./mvnw.cmd '-Dtest=ReportingTest#lifecycleReturnForRepairNeverReleasesEarly' test
```

上述场景实际创建、派单、维修、完工、验收并检查数据库和响应，不是仅构造目标状态后断言常量。原 A 的31项回归保留；旧迁移测试的预期数量随 V4 增加，未删除旧数据/响应断言；测试清理新增两张表。

测试有效性：python scripts/check_m3_repair_mutation.py 临时删除禁止自验收条件，观察预期403实际200的失败，再 finally 恢复源码。已实测成功；随后执行完整 verify 还原正常构建产物。日志位于 services/maintenance/target/repair-mutation.log，完整正常结果位于 target/surefire-reports（构建产物不提交）。

## 人工验收清单

1. 阅读本方案和 API；确认当前被派人维修、报修人验收、禁止自验、说明长度规则。
2. 复跑上面命令；核对完整链路、返修、权限隔离、重复请求与并发，按需阅读真实 SQL 断言。
3. 检查页面变化及 M1 未确认提示。当前不能按正式入口方式演示，原前端 M3 开关和生命周期服务开关默认关闭，公共代理未改变。
4. 与 A 评审 V4 迁移和文件归属、共享前端/CI；与 D 确认权限；与 M1 确认限制协议。评审前不要指向共享数据库启动应用，Flyway 不受业务开关控制。
5. 人工确认后再按仓库流程小提交、PR、评审合并；本轮没有替人签字，也未提交或推送。

## 未完成与风险

超时升级 CF-M3-006、评价 CF-M3-007 维持 planned。没有 M1 发送/回执、通知、管理员代验、真实 D 候选或生产部署；M1 下架窗口和学生免违约仍需共同联调。当前本地分支的需求编号名称源自旧草案，实际新需求使用 004/005，不覆盖同学 002 查询需求。

首次 Review 可以使用功能测试说明首轮真实持久化与错误恢复；完整浏览器业务演示要先落实隔离演示入口或完成共享入口评审，不能把页面单元测试当作浏览器演示已通过。

## 本轮最终结果

- 后端完整 verify 成功：40 项（ReportingTest 37、EligibilityGuardTest 3），零失败/错误/跳过，成功打包。
- 前端 11 个测试文件、46 项通过；vue-tsc 与 Vite 生产构建成功。
- 仓库登记检查：4 模块、22 项需求；git diff --check 通过。
- 破坏测试：移除自验收保护后预期 403/实际 200，测试失败；恢复后最终 40 项完整回归通过。
- 基础迁移 V1/V2/V3 与 A 提交逐字节相同；V4 在空库重放、V1/V2 旧数据升级及重复迁移检查中通过。
- 未运行浏览器端到端演示、真实 D/M1 联调或远程 CI；人工验收尚未填写。

## 用户命令验收与分支提交授权

2026-11-03，用户明确表示“我已经将命令验收，当前可以提交至仓库分支”。据此记录命令验收并提交/推送 B 独立分支；不表示浏览器演示、schema 同伴评审或真实 D/M1 联调已通过。前文“未提交/待人工验收”为交验时状态，当前授权仅更新此处确认范围。

本分支包含 A 8a5fdb2 交接基础，不修改同学远程分支，不合并 main。远程 main 已继续推进，正式 PR 前需评审分支依赖及主线差异；冲突由人处理。最终提交哈希以 Git 历史为准，不在提交内容中预写自身哈希。
