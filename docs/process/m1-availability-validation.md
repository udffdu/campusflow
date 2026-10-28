# M1 内部座位查询验证与人工复验

日期：2026-10-28。用户已明确同意 [分阶段查询方案](../architecture/m1-availability-plan.md)，并报告本批80测试和构建独立复验通过。下文分别记录 AI 验证与用户报告；本批据此分步保存为本地提交，远程 CI、PR评审与部署尚未完成。

## 实现及其范围

- `OpeningSchedule`：按上海日期和星期计算完整区间覆盖；空规则视为关闭，相邻区间可衔接，有间隙则拒绝。无效或重叠的规则拒绝输入。现有 LocalTime 输入不能表达 24:00，不推断跨午夜开放；D 的午夜表示仍待评审。
- `SeatAvailabilityQuery.query(scope,room,seats,period)`：内部 Java 调用，复用时间参数校验，检查授权范围输入、教室/座位归属一致性和基础有效性，组合开放条件与个人占用，按座位 code、id 排序。
- `ReservationStore.personalOccupancy(userId,roomId,period)`：一条 SQL 在同一语句快照内检查本人跨教室重叠，以及目标教室已占用座位。仅 CONFIRMED、CHECKED_IN 阻止半开区间重叠。
- 查询无预约写入、无占座锁；只返回座位 ID/编号及结果原因，不返回他人的身份或预约详情。数据库读取失败抛出 STORAGE_UNAVAILABLE，不伪装成空座位列表。

内部结果原因：NONE（计算正常，允许列表为空）、ROOM_INACTIVE、OUTSIDE_OPENING_HOURS、USER_TIME_CONFLICT。未授权范围抛出 SecurityException；资源快照不一致抛出 IllegalArgumentException；这些尚未映射 HTTP 状态。

`AccessScope` 只是内部输入，不验证会话、不自动从用户院系推导权限。未来 D 适配层必须完成身份、业务权限、访问范围及资源类型映射，不能把它绑定为浏览器请求体。RoomSnapshot/SeatSnapshot 是计算输入，不是 D 的共享实体或第二套主数据。

本批没有迁移或依赖变更，没有修改 D/公共契约、前端及现有演示服务。插座语义、M2/M3、D 网络适配和最终提交时完整条件复核仍未完成；当前结果不等于完整可预约 API。创建事务原有占用复核保持，但不能据此推定已有完整开放/权限校验。

## 实际本地验证

通过外层工作区的 `bash scripts/check-m1.sh` 执行 Maven verify：

- BUILD SUCCESS，80 个测试，0 失败、0 错误、0 跳过。
- 原有 ReservationRulesTest 24、ReservationStoreTest 19；新增 OpeningScheduleTest 17、SeatAvailabilityQueryTest 20。
- 完成于 2026-10-28T14:02:51+08:00，耗时 3.105 秒；完整输出在外层 `tmp/m1-availability-verify.log`。
- 首次受限环境运行因禁止监听端口，两个 PG 测试类初始化失败；随后在允许本机监听的执行环境中运行以上完整测试并通过。没有通过跳过 PG 用例规避限制。

两个 PG 测试类各自启动独立临时数据库，仅监听 127.0.0.1；查询组通过真实创建事务准备记录。状态更新和临时重命名表仅用于隔离数据库内的测试夹具/故障注入，不是生产迁移或签到实现。

| 行为 | 验证证据 |
| --- | --- |
| 完整覆盖、左右边界、连续区间、间隙、空规则、星期与上海日期 | OpeningScheduleTest 的覆盖/间隙/日期用例 |
| 实际组合开放与查询，结束越界拒绝 | SeatAvailabilityQueryTest.entirePeriodMustBeOpen |
| 目标座位被他人新预约后，下一次查询发生变化 | bookingAfterQueryChangesNextQueryWithoutReservingOnRead |
| 读取前后三表内容一致，不产生预约或事件 | 同上，比较 reservations/requests/outbox 全部行快照 |
| 五种状态的占用语义、相邻允许、未来及其他教室记录不误伤 | occupancyReadsAllFiveStatusFixtures 等真实 PG 用例 |
| 本人跨教室重叠有明确原因，终结状态不继续阻止 | ownOverlapInAnotherRoomReturnsExplicitConflict、terminalOwnReservationDoesNotBlockAnotherRoom |
| 基础停用过滤、范围缺失/未授权、归属不一致、重复 ID | baseInvalidRoomAndSeatAreExcluded 及 scope/资源输入用例 |
| 参数化时间规则在查询中实际执行 | queryAppliesConfiguredTimePolicy 的四组输入 |
| 正常空结果与数据库故障区分 | databaseFailureIsNotAnEmptySuccess |

所有测试直接运行被测业务和 SQL，不 mock 掉计算、数据库或事务逻辑。授权输入用例仅验证内部边界，不能作为真实 D 鉴权通过的证据。

附加检查已通过：仓库登记（4模块、27需求）、JSON状态/证据路径、说明文档链接、破坏脚本语法及git diff --check。原有15条需求和原43个测试源码保持不变；D源码、前端、公共契约、CI配置和迁移文件均无本批改动。既有seats测试任务会自动发现新增用例，远程CI仍未运行。

## 隔离破坏实验

`scripts/check_m1_core_mutations.py` 现在包含六项实验；本轮均在一次性源码副本执行。结果位于外层 `tmp/m1-availability-mutations/summary.json`，每项均为预期断言失败、测试执行错误为 0，原始 Java 源码 SHA-256 保持不变。

| 故意破坏 | 检测到的断言失败数 |
| --- | --- |
| 相邻端点误判重叠 | 3 |
| 移除提前窗口上限 | 2 |
| 移除数据库事务锁 | 1 |
| 移除查询开放时间筛选 | 1 |
| 移除已占用座位筛选 | 1 |
| 移除本人跨教室冲突判断 | 1 |

脚本要求指定行为用例失败；编译错误、环境错误或没有运行预期测试不算检测成功。正式源码已通过完整 80 测试；破坏实验没有改动原始实现，无需还原或重新制造正式失败。

## 用户独立复验记录

leafwhisper286提供Maven结果：80个测试，0失败、0错误、0跳过，BUILD SUCCESS；完成于2026-10-28T14:20:20+08:00，耗时3.113秒。

记录范围为本地核心、存储及内部查询的构建/测试复验，不声称用户逐行审阅源码、完成浏览器预约、D/M2/M3真实联调或其他成员评审。前一阶段43测试的人工复验记录继续保留，本次80测试报告单独记录。

AI据此分步本地提交查询实现/测试及说明材料，推送、远程CI、PR评审和合并另行记录。

查询实现/测试本地提交：`5a47bd0`（CF-M1-001/002），包含开放计算、个人占用读取、组合查询、37个新增测试及扩展破坏实验。说明、需求映射和本复验记录另作文档提交；完整编号以git log为准。

## 后续重复验证与学习

1. 理解“整个区间开放”和“查询不会占座”。例如开放 09:00—12:00 时，11:00—13:00 被排除；09:00—10:00 的已有预约不阻止 10:00—11:00。
2. 可阅读 OpeningSchedule.covers 和 SeatAvailabilityQuery.query，观察它们分别负责时间覆盖和组合筛选；不需要现在学习 D 内部代码或数据库安装。
3. 在当前电脑任意终端目录运行下面命令。脚本选择已有工作区 Java/cache，离线构建 M1，创建临时测试数据库，不启动或停止演示：

```sh
bash "/Users/lily/Documents/Codex/2026-10-22/qin/scripts/check-m1.sh"
```

预期末尾为 `Tests run: 80, Failures: 0, Errors: 0, Skipped: 0` 和 `BUILD SUCCESS`。测试时钟固定，不会因执行日期变化而误报过去时间。将结果发给 AI；若失败请提供末尾输出，不需要提供密码或凭据。

上述复验现已由用户报告通过。后续仅在代码变更或出现新问题时按需重复，不要求为保存同一批提交无故再次运行。下一阶段推进 D 接入所需的具体评审。
