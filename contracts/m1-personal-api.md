# 普通M1应用个人预约

2026-10-29。用户明确将跨模块联调/接口确认延后，先完成普通应用个人预约。本文描述已实现的M1个人入口，不是云部署/全系统验收授权；M2整室占用、M3维修限制尚未校验。沿用[本人历史](m1-history-api.md)、[原提交协议](m1-reservation-demo-api.md)中的会话、请求体、幂等及恢复规则，不新增D API或schema。

## 运行与启用

普通生产jar中由ReservationWriteConfiguration显式装配共享ReservationHandler和ReservationWriteAccess，不需要测试配置/测试classpath。`M1_WRITE_ENABLED`默认false；此时POST仍405、GET令牌404。启用时必须提供以下参数：

| 参数 | 含义 |
| --- | --- |
| M1_WRITE_ENABLED=true | 打开普通个人创建路由，其他写方法/未知写路径继续关闭 |
| M1_BROWSER_ORIGIN | 浏览器实际Origin，只有协议/主机/可选端口，无路径或末尾斜杠；远程站点要求HTTPS，HTTP仅localhost/127.0.0.1/[::1] |
| M1_CSRF_SECRET | 至少32随机字节的Base64，独立于M1_SERVICE_KEY；不提交或输出真实值 |
| M1_DATABASE_URL/USER/PASSWORD | M1自己的持久化PostgreSQL，Flyway只管理m1 schema；不连接D业务表 |
| M1_D_BASE_URL/SERVICE_KEY | D原有服务地址及M1调用D凭据 |
| M1_ADVANCE_MODE | 默认CALENDAR_DAYS，可显式ROLLING兼容旧演示 |
| M1_MAX_ADVANCE | 提前窗口，生产自然日模式要求0～365整日Duration；当前用户选择1天，即PT24H |
| M1_MAX_DURATION | 正整数小时Duration，当前PT4H |

启用时不合法Origin/缺失秘密/弱长度/复用服务凭据不能启动成功。显式配置后，使用正常`java -jar services/seats/target/seats-0.1.0-SNAPSHOT.jar`运行。密钥经环境注入，不写进命令行参数或Git；退出应用不删除外部数据库。测试预览另用一次性PG，退出预览会清理，仅为方便本机验收，不表示生产必须使用临时DB。

## 时间规则

用户已明确选自然日。窗口约束预约开始时间：在Asia/Shanghai取得服务端“今天”，可开始的日期≤今天+配置天数；开始时间仍不能早于当前时刻。1天时，明天22:00开始可通过窗口，后天00:00开始拒绝，即使它距现在不足/接近24小时，也按日期判断。查询与创建使用同一ReservationPolicy，新请求在外部验证之后、事务锁内再检查当前时钟。

结束继续受整点、最多4小时及完整开放区间限制；窗口不额外约束结束日期。D当前LocalTime开放格式无法表达24:00，本轮未改D或伪造跨午夜开放；涉及未开放分钟/边界仍拒绝。ROLLING继续按旧maxAdvance时长计算，原演示显式使用此模式，不将旧24小时具体化误记为用户正式选择。

## 端点和返回

GET `/api/m1/v1/reservation-capabilities`增加：stage=PERSONAL、canCreateReservation（普通启用且有创建权限）、canCreateDemoReservation=false、canReadOwn、advanceMode/advanceDays/durationHours、uncheckedSources=[M2,M3]。完整跨模块条件未实现，productionReady仍false。PERSONAL标志是当前入口模式，不是历史创建来源；预约来源仍为M1，不能从展示stage推断占用已校验M2/M3。

GET `/api/m1/v1/csrf`和POST `/api/m1/v1/reservations`在启用后由正常配置提供。会话绑定HMAC令牌有效15分钟、随机nonce、常量时间比较；POST要求精确Origin、同源/none Fetch Metadata（如浏览器提供）和单值X-M1-CSRF-Token、Idempotency-Key。只接收roomId/seatId/startAt/endAt，不接收身份/状态/来源/版本伪造。

POST新建201，原键同意图200/replayed=true，原键不同意图409；每次含锁内在线鉴权，重放先于新资源/时间校验，只对新建检查当前D资源/范围/启停/开放、最终时钟、个人重叠和座位重叠。预约/请求/PENDING事件原子写入；未新增事件派发器。返回沿用记录字段但stage=PERSONAL，productionReady=false。

本人单条/请求恢复/游标列表/repeat-context保持原权限及本人隔离。D失联/凭据错误、DB事务启动失败为503，不能当作正常空或成功；重试保留原键。应用重启仍用同一数据库时，历史/请求恢复保留。CSRF秘密轮换后旧令牌403，新令牌可重放已提交的原请求。

## 页面

普通入口`/seat-reservations`，导航“座位预约”，确认/提交/保存不再使用演示文案；整点日期＋小时控件保留，配置窗口从能力接口显示。“我的预约”按当前能力跳普通入口再次预约，旧fixture环境仍跳原演示入口。请求不确定时先恢复原键，不能通过历史复用另发重复创建。旧`/seat-reservation-demo`仍保留，旧能力不能在普通页面提供提交按钮。

页面继续明确个人预约边界与未接入的M2/M3，不把候选canReserve=false改为全资源可预约承诺。用户可先验收个人功能，之后接入统一来源；涉及M2/M3/B/D新增公共规则的评审仍按后续阶段执行。
