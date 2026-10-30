# M1-B 接入说明书

更新：2026-10-30。版本：阶段交接稿，尚未向B发送。CF-M1-007 已实现并由用户确认当前页面流程；CF-M1-008/009 的代码、本地模拟邮件、站内信与前端弹窗也已完成，真实 SMTP、最新完整隔离重跑及 D 负责人评审仍待完成。M1-A跨模块来源接入及 CF-M1-010/011 仍待后续。

## 当前接入摘要（优先于下文历史阶段描述）

入口见[交付索引](m1-a-delivery.md)和[续做手册](m1-a-resume.md)。普通jar可按显式安全配置开放个人写入，默认关闭；当前窗口为上海自然日的今天/明天内开始，单次默认最多4小时。查询、创建/恢复、本人历史/复用已实现并验收，契约见[查询](../../contracts/m1-query-api.md)、[普通预约](../../contracts/m1-personal-api.md)、[历史](../../contracts/m1-history-api.md)。

B现已在 M1 自有 V2 迁移和 `ReservationStore.checkIn` 中实现本人签到：按既有用户/教室锁顺序，锁内重读并再次在线授权，使用状态/版本条件将 `CONFIRMED` 原子更新为 `CHECKED_IN`，同时写转换和唯一 outbox 事实。每日码、展示页、CSRF/Origin、数据库限流、Web 输入、服务端计时使用页和 `ReservationStore.leaveSeat` 主动退座均已实现；退座把 `CHECKED_IN` 原子更新为 `COMPLETED`。契约见[签到API](../../contracts/m1-check-in-api.md)，验证见[本轮记录](../process/m1-check-in-validation.md)。CF-M1-008/009 又增加 V3 提醒 outbox 租约、15/10 分钟调度、D资源补全、站内信/邮件双通道和前端弹窗，见[提醒API](../../contracts/m1-reminder-api.md)与[提醒验证](../process/m1-reminder-validation.md)。尚无超时取消/违约、自动完成或运营查询；M2/M3来源仍未接入。

下文第5节和第7～10节保留阶段演进证据；其中早期“签到未实现、HTTP/创建未实现、滚动24小时建议、普通写入关闭”等语句仅代表当时，不覆盖本摘要和第11节。

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
- 早期曾建议签到窗口为 `[startAt,startAt+15分钟)`；2026-10-30 用户已决定当前 CF-M1-007 暂不限制签到时间，并允许学生主动退座完成本次使用。最终窗口与 NO_SHOW 竞态仍须在 CF-M1-010 实现前重新评审。

## 4. B 后续接入约束

1. 用户身份来自已验证会话，不从请求体信任 userId；业务权限与 D 的接入仍待正式契约确认。
2. 采用方案 B：个人预约及影响其有效性的操作保护用户和教室；整室/维修变化保护教室。按统一顺序取得数据库事务锁，锁后重读状态；不能自行绕过协调入口直接改有效状态。
3. 超时取消、释放与违约记录必须同一业务事务，待发通知持久化后异步重试；重复任务不能重复违约。具体方法/表尚未实现，交接前补充真实签名。
4. 签到与超时同时发生时用时间条件、当前状态和版本共同判断，不仅按请求到达顺序决定；合法签到后不能再被超时任务记违约。
5. M2/M3 与个人占用来源隔离，维修导致的系统终止不得记作学生违约。

## 5. 当前可用代码与实施状态

当前范围：`services/seats` 内包含五种状态、时间区间、参数化时间校验、真实PostgreSQL预约存储/幂等/事务锁，以及内部开放计算和个人占用查询。服务已具备只读HTTP/D适配，本轮另新增仅隔离环境装配的个人预约提交；仍没有B签到/违约任务。验证见 [核心规则](../process/m1-core-validation.md)、[存储及事务锁](../process/m1-storage-validation.md) 和 [内部查询](../process/m1-availability-validation.md)。

用户已同意 [座位查询方案](../architecture/m1-availability-plan.md) 的分阶段范围，内部查询已实现。它与完整鉴权及 M2/M3 集成有明确边界；查询不占座，终结个人预约也不自动解除外部来源限制。本批80测试已由AI本地运行通过，用户随后报告2026-10-28T14:20:20+08:00独立复验80测试及BUILD SUCCESS，耗时3.113秒。

- `ReservationStatus`：表示用户确认的五种状态，并结合预约区间判断是否阻止一个请求区间。
- `TimeRange`：不可变非空正区间及相交判断，支持相邻区间衔接。
- `ReservationPolicy`：注入服务端时钟、提前窗口和单次时长参数，按 Asia/Shanghai 校验整点、过去时间、窗口和时长。

该阶段曾建议滚动24小时；2026-10-29用户已选择自然日，普通入口现在按CALENDAR_DAYS执行，旧演示显式ROLLING。内部开放计算已实现；真实D授权/主数据与插座已用于只读查询和隔离个人提交；M2/M3限制仍待接入，不能只通过时间与个人占用计算就宣称座位完整可预约。

## 5.1 已实现的存储接口与迁移

V1__reservations.sql属于M1自己的m1 schema，包含预约、创建请求幂等记录、待发业务事件三表；Flyway初始化需指定schemas/defaultSchema为m1。临时数据库已验证空库迁移和重跑；正式环境尚未部署。

内部Java调用：

- `new CreateReservation(userId,roomId,seatId,period,resourceVersion)`：必须传入已由应用层认证/核实的身份与主数据，不是直接接收前端自报标识。
- `ReservationStore.create(request,idempotencyKey)`：独立事务创建，返回ReservationRecord。相同用户/键/意图重放返回同一记录当前状态，不重复产生事件。
- `ReservationStore.findForUser(userId,reservationId)`：仅匹配该用户；不存在或不属于本人均为Optional.empty。调用方仍须认证userId。

记录字段：id、userId、roomId、seatId、period(startAt/endAt)、status、source=M1、resourceVersion、version、createdAt；数据库还保存updatedAt。当前无更新/签到/取消方法，B不可据此自行直接改有效状态；这些方法会在对应工作完成后补齐。

锁协议实际使用`campusflow:M1:user:<UUID>`与`campusflow:M1:room:<UUID>`的SHA-256前64位有符号整数，去重后按有符号数升序取得事务级锁，READ COMMITTED下锁后重查。任何新写入入口都应复用OccupancyLocks/统一业务协调实现，而非重写另一套不兼容的锁键。

每次create同时保存RESERVATION_CREATED待发事件，状态PENDING；尚无派发器或D投递回执。超时取消/违约等B操作需要后续统一事务入口，不能把现有create当作任意状态更新方法。create使用REQUIRES_NEW，不能假定受外层事务回滚控制。

暂时失败码STORAGE_UNAVAILABLE用于锁等待超时等可重试故障；USER_TIME_CONFLICT、SEAT_TIME_CONFLICT、IDEMPOTENCY_CONFLICT对应业务冲突。这些创建/冲突码尚未暴露为创建HTTP；本批仅新增查询HTTP。

本地验证：24个核心测试+19个真实PG测试通过；三个隔离破坏实验均被检测。尚未验证M2/M3与个人预约同室竞争，不能将本轮个人预约互斥推广成完整跨模块验收。

## 5.2 已实现的内部查询接口

- `OpeningSchedule(List<Interval>)`、`covers(period)`：Interval为星期、分钟级LocalTime开始/结束；完整覆盖才开放，相邻规则可衔接，空规则关闭。跨午夜完整覆盖在D表示约定前不能推断。
- `ReservationStore.personalOccupancy(userId,roomId,period)`：一条只读SQL返回PersonalOccupancy(userConflict,occupiedSeatIds)，只含个人预约；不泄露他人身份。失败抛STORAGE_UNAVAILABLE，不当作空结果。
- `SeatAvailabilityQuery(store,policy).query(scope,room,seats,period)`：scope为AccessScope(userId,allowedRoomIds)，room/seats为M1的强类型输入快照；内部结果Result(reason,seats)中的座位仅含id/code，排序稳定。
- 原因NONE、ROOM_INACTIVE、OUTSIDE_OPENING_HOURS、USER_TIME_CONFLICT。范围外抛SecurityException、快照不一致抛IllegalArgumentException；本批查询HTTP将权限越界映射403，上游快照异常503，详见查询契约。

AccessScope并不认证会话，也不是前端载荷；现有HTTP适配层已通过D核实查询身份、权限、范围及类型；未来创建适配仍须重新验证这些事实，不能仅凭这个构造器授权。查询复用ReservationPolicy和有效状态，不取占座锁、不写三表；提交仍要重新验证全部条件。现有create尚未组合开放判断或D/M2/M3检查，不能直接暴露为完整预约API。

后续新增17个开放规则测试及20个真实PG查询测试，全部测试80个通过；隔离破坏实验现共六项，均检测预期断言失败，原始源码未变。80测试包含AI本地结果和用户随后独立复验的报告；用户此前43测试的验收仍只覆盖原核心/存储阶段，未挪用为查询验收。

## 6. 交接材料与剩余事项

- [x] 实际预约/查询/历史API与错误码、身份/CSRF/配置：见本页当前摘要的三个契约。
- [x] 已落库字段、V1/V2/V3与五状态已记录；本人签到、使用状态、主动退座和两类提醒已实现，超时取消、违约及自动完成仍待后续设计实施。
- [x] 用户/教室锁协议已记录；创建冲突/幂等例子见普通契约和网络验收。B状态更新的版本冲突仍待对应实现。
- [x] CF-M1-008/009 的事件、去重键和站内信/邮件接口已记录；CF-M1-010 的违约事件与审计仍待其方案确定。
- [x] [续做手册](m1-a-resume.md)提供本机/仓库启动与检查入口；各阶段验证记录提供测试和迁移证据。
- [x] 已明确记录M2/M3联调尚未完成及当前限制；不将记录完备当作联调通过。
- [ ] 用户确认说明书与 A 的实际行为一致后，自行转发 B；不伪造 B 的接收或评审记录。

## 7. 本批只读HTTP补充（脚本和三项页面已复验，A尚未全部完成）

[查询契约](../../contracts/m1-query-api.md)：GET /api/m1/v1/rooms、seat-candidates；Cookie在线D鉴权，候选canReserve=false，不开放写入。M1独立应用仅自己的m1迁移/数据；D/PG不可用503、失效会话401、越权限/范围403。不得把现有ReservationStore.create直接挂HTTP而跳过D/开放/M2/M3/CSRF校验。

隔离环境及真实网络28项、M1123/D24/前端22与浏览器结果见 [验证记录](../process/m1-http-query-validation.md)。B签到、状态更新、提醒和违约仍未实现；本批不新增B权限/通知或派发器。原锁/状态协议继续有效，最终说明书由用户在A全部完成后转交，未记作B已评审。

## 8. A个人提交与边界重核（本轮脚本及三项页面验收已完成）

任务书已逐页重读，A/B不是原文自带拆分，见[M1-A边界](../requirements/m1-a-boundary.md)。本次只新增A个人提交、同键恢复、本人单条读取及安全/测试；不新增B签到/提醒/违约/运营，不实现M2审批/M3工单。

ReservationStore.createValidated(intent,key,authorize,validateNew)在相同用户/教室锁和REQUIRES_NEW事务内先执行在线鉴权、再处理旧请求，只给新请求做D/开放验证及最终时间/个人占用检查。三表仍使用原V1，CONFIRMED和PENDING含义未改，没增加通用状态更新或事件派发器。findForRequest只供A本人恢复，不是B运营接口。

[隔离接口](../../contracts/m1-reservation-demo-api.md)和[验证/操作](../process/m1-reservation-submit-validation.md)描述实际行为。A记录/锁协议可供B后续接入，B的签到窗口、超时取消原子性、通知/违约等仍由B阶段决定实现；说明书待A全部完成后由用户转交，未记作B已接收或同意。

## 9. A本人历史与再次预约（脚本与指定页面已由用户确认）

已增加本人所有状态的游标列表，不修改状态或调用B签到/提醒/违约业务。B接入更新状态后，列表刷新读取新值，跨页不保证同一快照。仍遵守共享写入锁协议，不因展示历史就增设自动结束/取消逻辑。再次预约只复用原教室/座位，时段清空，读取当前D权限/范围/启停后由用户选择新时段并新建新键；保留原记录与版本，不复活终态。未知原提交先恢复，不能借复用另发请求。

契约[本人历史/复用](../../contracts/m1-history-api.md)，验证/用户步骤[本轮记录](../process/m1-history-validation.md)。AI本地M1157/web46/TCP28+48/四项破坏实验通过；本轮用户验收、提交和远程CI未发生。说明书仍由用户在A完成后交接，未发送、未登记B本人批准。

2026-10-29历史阶段验收更新：用户已确认脚本、三组历史/再次预约页面结果，并在日期＋整点下拉和开始/结束时间标签调整后接受并继续。本轮按既定流程保存本地提交；仅本地验证和上述人工观察，未推送/PR/远程CI/部署或登记其他负责人批准。说明书继续累积，尚未发送。

## 10. 普通个人预约入口新增（脚本及指定页面已确认）

用户要求外部联调后补、先完成普通个人功能。新增正常jar配置写入口/正常页面，复用原ReservationStore.createValidated锁内事务/幂等/在线鉴权，五种状态、三表V1及PENDING事件不改，未新增B生命周期/违约/通知任务。自然日窗口已由A明确选择，普通配置默认CALENDAR_DAYS，旧演示显式ROLLING。

普通能力canCreateReservation与旧canCreateDemoReservation分开，返回stage是当前入口展示模式，不是预约创建来源或状态。B仍应读取M1记录/版本并遵守共享锁，不依赖前端stage判断来源或违约。详细[普通契约](../../contracts/m1-personal-api.md)与[验证](../process/m1-personal-runtime-validation.md)，目前164/50/网络28+48+53通过，用户已确认本批脚本及正常创建/历史复用/自然日窗口三项页面结果，按流程保存本地提交，B交接仍等A完成后由用户转交，未发送。

## 11. CF-M1-007 每日教室码、签到与使用状态（当前页面流程已确认）

2026-10-30 已加入 V2 迁移、每日 HMAC 教室码、授权显示页、本人签到、服务端计时使用页、主动退座、状态转换记录、唯一生命周期事件和数据库持久化错误限流。学生动作复用 `seats.reservations.read.own` 并强制本人记录；展示复用 `spaces.write` 并强制本院系，未修改 D 权限目录或 schema。独立权限和 ADMIN 跨院系范围仍可后续评审，不能记作 D 已同意。

当前体验阶段不限制签到时间；正确码把本人 `CONFIRMED` 预约转为 `CHECKED_IN` 并进入使用页，错误码清空后可重新输入。计时使用服务端保存的 `checkedInAt`、`completedAt` 和 `serverTime`；主动退座以 `CHECKED_IN -> COMPLETED` 完成本次使用。签到与退座都先取得既有用户/教室锁，在锁内重读、在线复核权限并以状态和版本条件更新；状态、转换和 outbox 失败整体回滚，重复请求不重复写事实。CF-M1-010 尚未实现，最终签到窗口和双方真实竞态需在超时任务完成前补评审/补验；当前不能宣称 NO_SHOW、违约或自动完成已交付。

实际契约见[签到API](../../contracts/m1-check-in-api.md)。用户已确认当前页面流程；本地 177 项 Java、57 项前端和构建结果是本轮后续调整前的历史证据，详见[验证记录](../process/m1-check-in-validation.md)。本节不作为后续提醒改动的测试或远程CI证据；整个项目仍未部署。

## 12. CF-M1-008 / 009 预约提醒（代码已实现，外部邮件验收待完成）

M1 V3 为既有 outbox 增加下次尝试、认领租约、送达时刻和安全错误码；`ReminderStore`只为仍为`CONFIRMED`的预约生成“开始前15分钟”和“开始后10分钟未签到”两类唯一事实，`ReminderWorker`在事务外读取 D 教室/座位信息并把同一稳定事件分别投递到 D 站内信和邮件端点。并发扫描使用`FOR UPDATE SKIP LOCKED`，网络失败保留 outbox 并指数退避。严格 exactly-once 不适用于 SMTP 不确定响应；应表述为业务事实去重、D接收去重和至少一次发送尝试。

D V4 增加规范化唯一用户邮箱和独立邮件任务；邮件默认关闭，真实 SMTP 凭据只从环境变量注入。站内信为持久记录，前端弹窗每3秒轮询，只提示基线之后的新通知并约10秒自动关闭；这不是WebSocket。消息标题/正文包含教室、座位、完整上海时间和操作建议。

`check-m1-reminders.sh`验证正式调度、去重、失败恢复和双通道；`start-m1-reminder-preview.sh`提供模拟邮箱；`start-m1-instant-reminder-preview.sh`通过测试 classpath 对每笔人工新预约立即插入两类事件，只用于查看内容，不能证明15/10分钟规则。用户已确认模拟页面效果；最新多预约即时预览修复后尚未重跑完整隔离，真实外部邮箱、D负责人评审、远程CI和部署均未完成。CF-M1-010 的最终签到窗口、NO_SHOW竞态、取消释放与违约仍须单独实现。
