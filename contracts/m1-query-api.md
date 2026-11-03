# M1 浏览器只读查询 API v1

2026-10-28，A负责人leafwhisper286确认只读阶段后实现；本批脚本和三项页面检查已有用户复验，PR仍待进行。D既有契约不改；M2/M3内部接口仍是独立讨论稿。本页描述真实实现，不代表完整M1交付。

## 身份、结果含义

同源浏览器携带D的HttpOnly `CF_SESSION`，M1每次调用D `POST /internal/v1/authorization`，固定权限 `seats.availability.read`。身份/院系/权限不由查询参数提供。`spaces.read`/`spaces.write`或ADMIN身份不能代替座位业务权限；DEPARTMENT须院系匹配，CAMPUS仍须业务权限。没有本地登录或默认密码。服务凭据只在服务器环境注入。

本阶段**只有GET**；其他方法统一405和Allow: GET。没有预约、历史、签到或M2/M3写接口。所有响应不缓存，响应头 `X-Request-ID` 用于定位请求。未预期异常500仅返回安全消息与requestId。

候选查询只组合D启停/范围/开放/插座与M1个人预约占用，不含M2整教室申请、M3维修；`stage=CANDIDATE_ONLY`、`canReserve=false`始终表示本阶段没有预约提交能力。没有占座或生成事件；查询结果不是跨服务同一时刻快照，后续创建须重新验证。

## GET /api/m1/v1/rooms

参数 `page` 缺省0，范围0～100000；`size` 缺省20，范围1～100。仅这两项参数，未知或重复参数400。

返回 `{items,page,size,total}`。教室字段：`id,code,name,bookingScope,openingHours`。开放时间为D每周规则 `{day:1..7,start,end}`，上海时间；空规则不开放。列表仅含当前有效、本人院系可查询或CAMPUS开放的ROOM；total在过滤后计算，再进行浏览器分页。

M1通过服务凭据读取D全部必要页面，不要求学生拥有D目录权限，不把服务目录全部转发给学生。ROOM管理归属departmentId不改变个人预约范围规则。

## GET /api/m1/v1/seat-candidates

| 参数 | 规则 |
| --- | --- |
| roomId | 必填，规范UUID |
| startAt / endAt | 必填，UTC ISO-8601并以Z结尾；上海时区整点，结束晚于开始 |
| requiresPower | true或false，缺省false；精确匹配具体SEAT的POWER_OUTLET |
| page / size | 同教室列表 |

未知参数、重复单值、非法bool/分页/时间400。提前窗口和最大时长由服务配置注入，不能预约过去。24小时是测试/演示参数，未作为正式“1天”解释获批。

返回字段：`roomId,startAt,endAt,reason,items,page,size,total,stage,canReserve`。items为 `{id,code,hasPowerOutlet}`，按code/id排序，total在全部过滤后计算。不含他人身份和预约详情。

reason：`NONE`、`ROOM_INACTIVE`、`OUTSIDE_OPENING_HOURS`、`USER_TIME_CONFLICT`。NONE也可为空数组；其他原因是正常200空结果。本人在其他教室的重叠有效预约仍产生USER_TIME_CONFLICT。

## 错误

错误体 `{code,message,requestId}`。

| HTTP | 含义 |
| --- | --- |
| 400 | 参数错误；NOT_WHOLE_HOUR/IN_PAST/ADVANCE_WINDOW/DURATION_LIMIT/INVALID_INTERVAL等时间规则 |
| 401 SESSION_REQUIRED | 无Cookie、格式无效或D确认会话已失效，前端重新登录 |
| 403 FORBIDDEN | 有效身份无业务权限/有效院系，或越个人预约范围 |
| 404 ROOM_NOT_FOUND | 指定ID不存在或不是ROOM |
| 405 READ_ONLY | 当前不支持写请求 |
| 503 D_UNAVAILABLE | D不可用、超时、服务凭据错误、必需字段/分页异常；不使浏览器退出登录 |
| 503 STORAGE_UNAVAILABLE | M1存储故障；不返回伪空结果 |
| 500 INTERNAL_ERROR | 未预期程序故障，不返回异常堆栈 |

D内部HTTP401/403表示服务连接/凭据故障，映射M1 **503**；只有D授权结果确认会话无效才返回浏览器401。分页检测总数变化、重复ID、类型/父级/院系不一致、短页/空页及上限，失败不能返回部分列表。允许未来增加非关键D响应字段，必需字段缺失/无效则拒绝，不默认CAMPUS。

## 配置与启动

M1独立Spring Boot应用 `io.campusflow.seats.SeatsApplication`，自身Flyway默认schema=m1，仅使用现有V1，无本批新迁移或D表访问。必需环境：

- M1_DATABASE_URL、M1_DATABASE_USER；M1_DATABASE_PASSWORD可为空（仅按实际数据库配置）。
- M1_D_BASE_URL：受控D根地址（http/https，不含凭据、路径、query或fragment）。不跟随重定向。
- M1_SERVICE_KEY：与D相同的M1服务凭据，至少32字符；不写入浏览器、仓库、聊天或日志。
- M1_MAX_ADVANCE、M1_MAX_DURATION：ISO Duration，均无正式缺省值；时长须正整数小时。

可选：M1_ADDRESS缺省127.0.0.1、M1_PORT缺省8081；M1_CONNECT_TIMEOUT=PT1S、M1_READ_TIMEOUT=PT2S、M1_CALL_BUDGET=PT3S、M1_MAX_PAGES=100（可配置1～1000）。D每页100、单个响应最多1MiB。预算在同一次查询的鉴权/资源/分页间共享，响应体也受超时限制；初版不自动重试，不缓存身份或资源有效性。

前端开发代理按`/api/m1`优先转M1，`/api`转D。`M1_PROXY_TARGET`/`D_PROXY_TARGET`可覆盖目标，`--port`可切换隔离入口；默认旧5173/8080保持兼容。本批未更新生产compose/网关或宣称部署完成。

复验、隔离演示及本地证据见 [HTTP验证记录](../docs/process/m1-http-query-validation.md)。未来写入CSRF、停用竞态、午夜、管理员配置和M2/M3仍需相应阶段决定，不能沿用本页的只读能力直接暴露现有create。
