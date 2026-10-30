# CF-M1-007 签到与使用状态验证记录

日期：2026-10-30。状态：用户已完成当前页面流程的人工确认；最新“不限制签到时间、使用计时、主动退座及多身份会话同步”调整按用户要求未重新运行自动化测试。没有 commit、push、PR、远程 CI 或部署。

## 当前人工确认范围

- 空间管理员可在教室详情进入签到码展示页，读取 A101 的当日 6 位码；
- 学生在“我的预约”输入正确码后进入使用状态页，状态为“使用中”，计时以服务端签到时刻为准；
- 学生主动退座并二次确认后，预约变为 `COMPLETED`，页面显示已退座并停止计时；
- 错误码会提示失败、清空输入并允许重新输入；
- 当前阶段不按预约开始或结束时间拒绝签到。

人工预览使用 `bash scripts/start-m1-personal-preview.sh`，访问脚本给出的 `http://[::1]:5374`。脚本会启动 D、M1、前端和 F 盘临时 PostgreSQL，并为 `student` 准备 A101 预约。`run-local-demo.sh` 只启动 D，不能代替签到预览入口。

管理员与学生同时操作时必须使用两个不同浏览器，或普通窗口与 InPrivate/无痕窗口。同一浏览器配置下的标签页共享 `CF_SESSION`，后登录身份会替换先登录身份；跨标签页会话同步用于阻止旧界面继续写入，不提供多账号隔离。

## 策略调整前的历史自动化证据

下列结果来自加入当前使用状态/退座流程及取消签到时间限制之前，只用于说明早期 CF-M1-007 的基础实现曾被检查，不能作为最新代码已复验的证据：

| 检查 | 历史本地结果 |
| --- | --- |
| M1 Java 测试 | 177 项，0 失败、0 错误、0 跳过，`BUILD SUCCESS` |
| 前端组件/接口测试 | 57 项，全部通过 |
| 前端类型检查与生产构建 | `vue-tsc --noEmit` 与 Vite build 通过 |
| PostgreSQL 迁移 | 空库依次执行 V1、V2 成功，schema 到 v2 |
| 真实 HTTP | D 故障注入服务、M1 TCP、真实 PostgreSQL 下的 `QueryHttpTest` 66 项通过 |
| 隔离普通应用 | 生产 JAR、独立 D、独立 PostgreSQL 共 57 项通过 |
| 真实并发 | 两个连接并发重复签到，只产生一次转换和一次生命周期事件 |
| 失败原子性 | 注入 outbox 写失败后，状态仍为 `CONFIRMED`，无转换残留 |
| 隔离破坏实验 | 当时的 6/6 指定破坏均被断言捕获，正式源码哈希前后不变 |

当时嵌入式 PostgreSQL、Maven、Java 临时目录均显式放在 `F:\COURSE\Software\PJ\campusflow\tmp\runtime`。一次误用系统临时目录的失败运行已中止，创建的两个 C 盘 `epg*` 目录已核实删除；历史成功结果来自 F 盘临时数据库。

## 当前实现应审查的重点

- HMAC 码按教室与上海日期隔离，在上海 00:00 轮换；密钥少于 32 字节拒绝启动。
- 本人隔离、锁内再次鉴权、CSRF、Origin、未知字段和请求大小边界。
- 当前签到不检查预约时间，只允许 `CONFIRMED -> CHECKED_IN`；版本、转换与 outbox 同事务。
- 错误码可重输，数据库失败计数仍在第 5 次封锁；重复签到不重复写事实。
- 使用页从服务端 `checkedInAt`、`completedAt` 和 `serverTime` 计算计时，刷新后状态可恢复。
- 主动退座只允许 `CHECKED_IN -> COMPLETED`，转换、版本及 `RESERVATION_COMPLETED` 同事务；重复退座幂等。
- 教室显示页要求 `spaces.write`、本院系、启用教室，响应禁止缓存。
- 页面不发送用户、教室、座位或状态伪造字段；不同身份必须使用隔离的浏览器 Cookie 环境。

## 尚未覆盖/尚未实现

- 按用户要求，最新代码未重跑 Java、前端、隔离 HTTP、并发或破坏实验，因此不能宣称当前源码自动化通过。
- CF-M1-008～011 未实现，不能把本记录称为完整 M1-B 验收。
- CF-M1-010 超时任务尚未实现；最终签到窗口以及“签到与 NO_SHOW 同时竞争”需在该任务实现前重新评审和验证。
- D 独立签到权限、跨院系 ADMIN 范围、D 审计派发仍需跨模块评审；当前使用既有最小可用权限并明确本院系限制。
- 未运行远程 CI、部署或生产密钥轮换演练。

## 代码审查入口

先查看[签到契约](../../contracts/m1-check-in-api.md)、V2 迁移、`DailyCheckInCode`、`ReservationStore.checkIn`、`ReservationStore.leaveSeat`、`CheckInHandler`、`RoomCheckInDisplayView.vue`、`MyReservationsView.vue` 和 `SeatUsageView.vue`。当前人工确认不等于自动化、远程 CI 或部署通过。
