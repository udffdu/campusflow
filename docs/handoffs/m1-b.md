# M1-B 接入说明书

更新：2026-10-28。版本：持续更新稿。A 尚未完成，此文尚未向 B 发送。

后续D补充已获用户直接实施授权：三项个人预约权限、ROOM.bookingScope、SEAT的POWER_OUTLET及M1内部规则已实现；用户已提供最新M1 86测试和前端11测试/构建复验成功输出，D24由同次本地报告补证，具体见 [各负责人说明书](m1-integration.md)。此授权不代表B/D本人已同意，也没有预先定义签到/运营权限。下文80和43为各阶段历史证据。

## 1. 决策来源与使用时机

leafwhisper286 已明确承担 M1-A/B 内部共享设计决策，并确认下述五种预约状态。B 的交接安排在 A 全部完成后，不以提前取得 B 的确认作为当前内部开发条件。这不是 B 本人已经评审同意的记录，也不改变 D/M2/M3 受影响接口/schema 仍须评审的约定。

本文随 A 的实现更新：代码接口、数据库字段、启动命令和测试结果以真正实现为准；不得据本文草案推定预约 API、签到、违约或部署已经完成。最终交接前补充可运行示例与完整验证记录，再由用户转发。

## 2. 分工

- A：查询、预约、本人历史/再次预约、统一并发占用、M2/M3 来源协调。
- B：每日签到码、签到、提前/迟到提醒、未签到取消和违约、运营查询。
- 共用：M1 数据模型、状态、版本比较、事务锁顺序及占用释放操作。B 后续接入时遵守这些规则，需求变化通过用户协调再修订。

## 3. 用户已确认的状态

| 代码值 | 含义 | 占用语义 |
| --- | --- | --- |
| CONFIRMED | 已预约、未签到 | 原预约区间内阻止冲突预约 |
| CHECKED_IN | 已签到 | 原预约区间内继续阻止冲突预约 |
| COMPLETED | 正常结束 | 不再阻止新预约，保留历史 |
| NO_SHOW | 因超时未签到取消 | 释放个人占用，恰好一次违约 |
| DISRUPTED | 因维修等系统原因终止 | 释放个人占用，免记学生违约 |

时间区间按 `[startAt,endAt)` 比较，相邻时段不冲突。DISRUPTED 释放的是个人预约事实，不解除维修工单或 M2 占用；这些限制必须按其具体来源分别解除。

用户确认状态含义；下面的具体转换、字段和窗口建议仍需由用户在实现对应功能前决定：

- 创建→CONFIRMED；合法签到→CHECKED_IN；正常结束→COMPLETED。
- 开始后15分钟未签到→NO_SHOW；系统原因取消→DISRUPTED；终结状态不能被旧请求复活。
- 签到窗口建议 `[startAt,startAt+15分钟)`；提前签到、用户主动取消、提前结束规则尚未最终确定，不因确认五种状态而自动批准。

## 4. B 后续接入约束

1. 用户身份来自已验证会话，不从请求体信任 userId；业务权限与 D 的接入仍待正式契约确认。
2. 采用方案 B：个人预约及影响其有效性的操作保护用户和教室；整室/维修变化保护教室。按统一顺序取得数据库事务锁，锁后重读状态；不能自行绕过协调入口直接改有效状态。
3. 超时取消、释放与违约记录必须同一业务事务，待发通知持久化后异步重试；重复任务不能重复违约。具体方法/表尚未实现，交接前补充真实签名。
4. 签到与超时同时发生时用时间条件、当前状态和版本共同判断，不仅按请求到达顺序决定；合法签到后不能再被超时任务记违约。
5. M2/M3 与个人占用来源隔离，维修导致的系统终止不得记作学生违约。

## 5. 当前可用代码与实施状态

当前范围：`services/seats` 内包含五种状态、时间区间、参数化时间校验、真实PostgreSQL预约存储/幂等/事务锁，以及内部开放计算和个人占用查询。它尚不启动HTTP服务、不访问D，也没有签到/违约任务。验证见 [核心规则](../process/m1-core-validation.md)、[存储及事务锁](../process/m1-storage-validation.md) 和 [内部查询](../process/m1-availability-validation.md)。

用户已同意 [座位查询方案](../architecture/m1-availability-plan.md) 的分阶段范围，内部查询已实现。它与完整鉴权及 M2/M3 集成有明确边界；查询不占座，终结个人预约也不自动解除外部来源限制。本批80测试已由AI本地运行通过，用户随后报告2026-10-28T14:20:20+08:00独立复验80测试及BUILD SUCCESS，耗时3.113秒。

- `ReservationStatus`：表示用户确认的五种状态，并结合预约区间判断是否阻止一个请求区间。
- `TimeRange`：不可变非空正区间及相交判断，支持相邻区间衔接。
- `ReservationPolicy`：注入服务端时钟、提前窗口和单次时长参数，按 Asia/Shanghai 校验整点、过去时间、窗口和时长。

其中“一天按滚动24小时且包含上边界”仍为具体化建议；核心接收参数，不把24小时绑定为用户已最终确认的默认配置。内部开放计算已实现；真实D授权/主数据适配、插座及M2/M3限制仍待接入，不能只通过时间与个人占用计算就宣称座位完整可预约。

## 5.1 已实现的存储接口与迁移

V1__reservations.sql属于M1自己的m1 schema，包含预约、创建请求幂等记录、待发业务事件三表；Flyway初始化需指定schemas/defaultSchema为m1。临时数据库已验证空库迁移和重跑；正式环境尚未部署。

内部Java调用：

- `new CreateReservation(userId,roomId,seatId,period,resourceVersion)`：必须传入已由应用层认证/核实的身份与主数据，不是直接接收前端自报标识。
- `ReservationStore.create(request,idempotencyKey)`：独立事务创建，返回ReservationRecord。相同用户/键/意图重放返回同一记录当前状态，不重复产生事件。
- `ReservationStore.findForUser(userId,reservationId)`：仅匹配该用户；不存在或不属于本人均为Optional.empty。调用方仍须认证userId。

记录字段：id、userId、roomId、seatId、period(startAt/endAt)、status、source=M1、resourceVersion、version、createdAt；数据库还保存updatedAt。当前无更新/签到/取消方法，B不可据此自行直接改有效状态；这些方法会在对应工作完成后补齐。

锁协议实际使用`campusflow:M1:user:<UUID>`与`campusflow:M1:room:<UUID>`的SHA-256前64位有符号整数，去重后按有符号数升序取得事务级锁，READ COMMITTED下锁后重查。任何新写入入口都应复用OccupancyLocks/统一业务协调实现，而非重写另一套不兼容的锁键。

每次create同时保存RESERVATION_CREATED待发事件，状态PENDING；尚无派发器或D投递回执。超时取消/违约等B操作需要后续统一事务入口，不能把现有create当作任意状态更新方法。create使用REQUIRES_NEW，不能假定受外层事务回滚控制。

暂时失败码STORAGE_UNAVAILABLE用于锁等待超时等可重试故障；USER_TIME_CONFLICT、SEAT_TIME_CONFLICT、IDEMPOTENCY_CONFLICT对应业务冲突。HTTP状态映射尚未实现。

本地验证：24个核心测试+19个真实PG测试通过；三个隔离破坏实验均被检测。尚未验证M2/M3与个人预约同室竞争，不能将本轮个人预约互斥推广成完整跨模块验收。

## 5.2 已实现的内部查询接口

- `OpeningSchedule(List<Interval>)`、`covers(period)`：Interval为星期、分钟级LocalTime开始/结束；完整覆盖才开放，相邻规则可衔接，空规则关闭。跨午夜完整覆盖在D表示约定前不能推断。
- `ReservationStore.personalOccupancy(userId,roomId,period)`：一条只读SQL返回PersonalOccupancy(userConflict,occupiedSeatIds)，只含个人预约；不泄露他人身份。失败抛STORAGE_UNAVAILABLE，不当作空结果。
- `SeatAvailabilityQuery(store,policy).query(scope,room,seats,period)`：scope为AccessScope(userId,allowedRoomIds)，room/seats为M1的强类型输入快照；内部结果Result(reason,seats)中的座位仅含id/code，排序稳定。
- 原因NONE、ROOM_INACTIVE、OUTSIDE_OPENING_HOURS、USER_TIME_CONFLICT。范围外抛SecurityException、快照不一致抛IllegalArgumentException；HTTP映射尚未实现。

AccessScope并不认证会话，也不是前端载荷；未来D适配层须核实真实身份、业务权限、开放范围与资源类型，不能仅凭这个构造器授权。查询复用ReservationPolicy和有效状态，不取占座锁、不写三表；提交仍要重新验证全部条件。现有create尚未组合开放判断或D/M2/M3检查，不能直接暴露为完整预约API。

后续新增17个开放规则测试及20个真实PG查询测试，全部测试80个通过；隔离破坏实验现共六项，均检测预期断言失败，原始源码未变。80测试包含AI本地结果和用户随后独立复验的报告；用户此前43测试的验收仍只覆盖原核心/存储阶段，未挪用为查询验收。

## 6. A 完成后补齐的交接项

- [ ] 实际预约/查询/历史 API 与错误码，以及身份、CSRF 与密钥配置。
- [ ] 已落库字段、迁移版本、有效状态及取消/签到/违约操作的真实调用入口。
- [ ] 用户/教室事务锁协议和版本冲突处理示例。
- [ ] B 需要的事件、去重键、通知与审计接口。
- [ ] 可独立启动的环境命令、虚构样例、测试及迁移验证结果。
- [ ] M2/M3 联调事实、未完成部分和已知限制。
- [ ] 用户确认说明书与 A 的实际行为一致后，自行转发 B；不伪造 B 的接收或评审记录。
