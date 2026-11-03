# CF-M1-008 / 009 提醒验证记录

日期：2026-10-30。状态：代码与本地验证工具已完成，用户已确认模拟提醒页面效果；真实 SMTP 服务商、远程 CI、D 负责人评审和部署未完成。本文不把测试加速模式当作正式时间规则证据。

## 自动化覆盖

`bash scripts/check-m1-reminders.sh` 依次执行 D、M1 与 Web 测试及前端生产构建，再启动隔离 PostgreSQL、D、M1、前端和本地 SMTP 捕获器。覆盖：

- 开始前 15 分钟只生成一次 `RESERVATION_START_REMINDER`；
- 开始后 10 分钟仍未签到才生成一次 `RESERVATION_LATE_REMINDER`；
- 已签到记录不生成迟到提醒；
- 并发扫描、重复调度、租约过期和重启恢复不重复业务事实；
- M1 到 D 失败时保留 outbox 并退避重试；
- D 分别持久化站内信和邮件任务，模拟一次 SMTP 失败后重试成功；
- 标题与正文包含教室、座位、完整上海时间和操作提示；
- 前端通知中心展示结构化内容；全局弹窗忽略历史基线、展示新通知、可关闭或跳转，并保留同时到达的多条通知。

此前一次完整本地隔离运行通过：D 27、M1 185、Web 60 和生产构建通过，端到端验证两类提醒、站内信、SMTP 首次失败重试和每类一次邮件。之后增加了更直观文案、全局弹窗以及多预约即时预览修复；按用户要求没有再次执行完整隔离检查。最新针对性结果为弹窗/通知视图 2 项测试和前端生产构建通过。以上均为本地结果，不代表远程 CI。

## 人工预览

普通模拟预览：

```sh
bash scripts/start-m1-reminder-preview.sh
```

终端会输出学生、管理员临时凭据和访问地址。使用不同浏览器环境登录不同身份，避免同源 `CF_SESSION` Cookie 互相覆盖。学生查看 `/notifications`，模拟邮箱查看 `http://127.0.0.1:8025`。

为了逐笔检查刚创建的预约，可运行：

```sh
bash scripts/start-m1-instant-reminder-preview.sh
```

然后以学生身份在预约页面连续创建多笔预约。脚本持续观察所有新预约，为每一笔通过测试夹具插入两类 outbox 事件；D/M1 正常工作进程仍负责资源读取、站内信和邮件持久化、去重与投递。预约原始时间不变，生产源码不包含该触发入口。此模式只用于观察内容和双通道链路，不能验收正式 15/10 分钟条件。

收到新站内信后，页面顶部出现临时弹窗；首次打开页面前已有的历史通知不会集中弹出。弹窗约 10 秒后自动关闭，可手动关闭或前往通知中心。通知中心记录不会因弹窗关闭而删除。

真实 SMTP 预览：

```sh
export CF_REMINDER_EMAIL='student@example.com'
export PLATFORM_MAIL_ENABLED='true'
export PLATFORM_SMTP_HOST='smtp.example.com'
export PLATFORM_SMTP_PORT='587'
export PLATFORM_MAIL_FROM='campusflow@example.com'
export PLATFORM_SMTP_USERNAME='...'
export PLATFORM_SMTP_PASSWORD='...'
bash scripts/start-m1-reminder-preview.sh --real-mail
```

真实参数必须使用发件邮箱的 SMTP 授权信息；收件邮箱只需要是可接收地址。不要把真实地址、密码或授权码提交到 Git。

## 待完成

- 使用真实 SMTP 服务商完成到达、垃圾箱、退信和限流验收；
- 重跑加入最新文案、弹窗和多预约修复后的完整隔离脚本；
- 由 D 负责人评审 V4 迁移、用户邮箱字段、内部邮件端点和运维策略；
- 在实际提交上确认远程 CI；
- 对“创建时已经错过提醒点”的产品处理作出决策。

## 与 CF-M1-010 的衔接

开始后15分钟仍为`CONFIRMED`的预约现由CF-M1-010转为`NO_SHOW`；未发送的前置/迟到提醒被标记为不再适用，另生成唯一`RESERVATION_NO_SHOW`通知事实并沿用同一D站内信/邮件投递链路。取消、释放、一次违约和页面红色历史的实现与证据见[CF-M1-010验证记录](m1-no-show-validation.md)。真实SMTP限制不因复用链路而改变。
