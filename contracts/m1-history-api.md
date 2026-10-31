# M1-A 本人历史与再次预约

2026-10-31。基础列表与复用属于M1-A；M1-B在不改变身份、状态或写入边界的前提下，增加本人历史的服务端状态/日期筛选，以便审查签到、完成与违约记录。参见[方案](../docs/architecture/m1-history-plan.md)、[验证](../docs/process/m1-history-validation.md)和[M1-B范围](../docs/requirements/m1-b.md)。沿用[只读](m1-query-api.md)与[提交](m1-reservation-demo-api.md)的CF_SESSION、在线D鉴权、错误格式、no-store、服务凭据及503规则；本扩展没有新增D接口或数据库迁移。

## 本人列表

`GET /api/m1/v1/reservations?size=20&status=NO_SHOW&from=2026-10-29T16:00:00Z&to=2026-10-31T16:00:00Z&cursor=...`

权限为`seats.reservations.read.own`；不需要创建或空间管理权限。用户ID只来自D在线鉴权，SQL每次包含本人筛选。只允许`size`、`cursor`、`status`、`from`和`to`，重复或未知参数返回400。`size`默认20，范围1～50；`status`可为五种预约状态之一。`from`与`to`必须同时提供为UTC `Z`时间，满足`from < to`且跨度不超过366天，筛选条件为`startAt >= from && startAt < to`。Web把上海开始日期零点和结束日期次日零点换算为该半开UTC区间，因此页面上的结束日期包含全天。首个请求省略`cursor`（显式空值400），后续原样使用`nextCursor`，不拼装页码或用户编号。

响应：`{items:[<本人记录>],size:20,nextCursor:"...",serverTime:"..."}`。记录字段沿用本人单条读取，含预约ID、教室/座位ID、时段、状态、记录/资源版本和PERSONAL_DEMO标识，并增加`checkedInAt`、`completedAt`、`usageDurationSeconds`和`violationReason`。使用中时长以同一响应的服务端`serverTime`为截止点；已完成时长以保存的完成时刻为截止点。未签到违约返回`violationReason="NO_SHOW"`，从未签到的记录时长为0。包括CONFIRMED/CHECKED_IN/COMPLETED/NO_SHOW/DISRUPTED，不会因为当前资源已停用而隐藏旧记录，不在读取时改变状态。空列表为200且items=[]；nextCursor为空字符串表示没有后续页。

排序为created_at降序、UUID降序，额外读取1条判定是否有下一页，SQL最多取51条。游标为URL安全Base64、无padding，编码“当前用户与筛选条件的SHA-256指纹 + createdAt(UTC) + reservationId”，最大200字符；仅定位，不是秘密或授权凭据。用户或筛选条件不匹配、编码/时间/UUID无效均返回400。边界为`(created_at,id)<(上页末条时间,上页末条ID)`，翻页期间新插入较新记录不会推移旧边界。页面之间不是同一事务快照；状态变化或刷新可能显示新内容。

历史不逐条调用D补充资源名称，避免N+1和伪造历史快照；签到/完成时刻与违约原因也由一次聚合SQL读取，不按记录追加查询。返回保留的资源标识/当时版本，当前教室/座位名称在复用上下文读取，不声称是预约时名称。CF-M1-010 的V4迁移增加生命周期查询索引；仍未做生产规模性能验证。

## 再次预约上下文

`GET /api/m1/v1/reservations/{id}/repeat-context`

无查询参数。顺序：D验证本人读取→按本人查原记录→重新验证查询权限→D读取当前教室/座位→院系范围/启停/座位归属。即使D管理权限足够也不替代M1业务权限。外人/不存在404（不读资源来暴露外人的座位），原资源不存在404，停用409 RESOURCE_INACTIVE，范围/查询权限变化403，D/DB故障503。上下文仅供查询，最终新建仍独立验证。

响应包含reservationId、room（id/code/name/bookingScope/openingHours）、seat（id/code）、requiresNewTime=true、canReserve=false；不返回旧时段、幂等键或资源版本作为新请求输入。它不写任何M1表、不复制预约、不宣称原座位当前可约。

## 前端与写入

`/my-reservations`入口要求本人读取权限；只具读取权限的账号能访问并以此作为登录落点（没有空间或查询权限时）。提供单一状态、上海开始/结束日期筛选以及游标上一/下一页、刷新回第一页和错误重试；应用、清除筛选或切换用户都会回到第一页，筛选在服务端执行而不是只过滤当前页。页面以明确文字、图标和颜色共同区分待签到、签到成功/使用中、签到成功/已完成、未签到违约及系统终止；签到记录显示服务端签到时间和使用时长，完成记录同时显示结束时间。颜色不是唯一状态信息。无演示创建能力或无查询权限时，不显示再次预约按钮。

“再次预约”导航到`/seat-reservation-demo?repeatReservationId=<原预约ID>`。服务端重新核验本人上下文；页面只预填当前教室/座位，起止时间清空，用户必须重新填写整点时段。查询结果分页中只提供原座位的选择，不默默替换其他座位；原座位不在此页时可翻页或明确退出复用再选择。选择前再次查询，POST沿用现有独立CSRF/Origin、锁内在线鉴权、新请求实时资源/开放/时钟/个人占用检查。新预约使用新键，旧记录不变；旧键只用于恢复原未知请求。

尚有pending提交时，先恢复原请求，历史页面不允许据此另发创建；直接带复用URL也不能绕过。不在历史页自动POST。退出/用户切换清理已有草稿机制保留，列表丢弃旧用户/旧请求的迟到响应。CF-M1-010生命周期现已接入历史摘要；M2/M3占用来源、完整正式放行及跨服务停用补偿仍属后续范围，CF-M1-006不因此登记完整业务验收。
