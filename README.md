# CampusFlow · 校园资源协同平台

以自习座位预约为核心，连接整教室申请审批与物业报修的课程实践项目。

当前阶段：D 公共层与前端公共外壳已实现；M1-A 个人预约及 M1-B 的 CF-M1-007 每日教室码、本人签到、使用计时与主动退座已实现，当前页面流程已由用户确认。最新使用状态/退座调整尚未重新运行自动化检查；提醒、未签到违约、自动结束、运营查询、M2/M3 业务及跨模块契约仍未完成；尚未部署华为云。

## 已确认的上下文

- 团队 6 人，模块已分工；`dianashiba` 负责 M2 和公共层 D。
- 技术版本：Java 21、Spring Boot 3.5.16、PostgreSQL 17.11、Vue 3.5.44、Vite 8.3.5；Maven Wrapper 与 npm 锁文件已加入。
- GitHub 公开仓库：[udffdu/campusflow](https://github.com/udffdu/campusflow)。
- 用户已确认课程允许公开仓库。远程创建与保护规则是否生效，以实际 GitHub 状态为准。
- 华为云服务器尚未申请；先完成必做项，自然语言业务代理暂不进入开发范围。

## 目录

```text
apps/web/                 Vue 3 前端，按业务模块组织页面
services/platform/        D：身份权限、空间主数据、通知、审计
services/seats/           M1：个人预约、签到、违约、资源占用
services/rooms/           M2：教室申请、多级审批、超时撤销
services/maintenance/     M3：报修、派单、维修、验收
contracts/                OpenAPI 和跨模块接口契约
spec/                     机器可读项目上下文、责任归属、需求登记
docs/architecture/        架构与决策记录
docs/requirements/        需求讨论与待决问题
docs/process/             分支、评审、发布和 GitHub 配置
infra/                    本地、测试、生产环境配置的归属约定
scripts/                  仓库检查及未来运维脚本
.github/                  CI、Issue 和 PR 模板
```

## 开始协作

1. 阅读 [协作规则](AGENTS.md) 和 [贡献流程](CONTRIBUTING.md)。
2. 核对 [模块边界](docs/architecture/module-boundaries.md) 与 [接口清单](contracts/README.md)。
3. 在 [需求登记](spec/requirements.json) 中找到任务，明确验收标准；涉及模块共同评审后再实现。
4. 创建短期任务分支，通过 PR 提交小范围变更，由其他成员验收。

运行当前阶段的仓库结构和需求登记检查：

```sh
python3 scripts/check_repository.py
```

该脚本只检查仓库登记。应用验证请执行：

```sh
cd services/platform
./mvnw verify
cd ../../apps/web
npm ci
npm test
npm run build
```

后端测试启动真实 PostgreSQL 17，覆盖认证、范围权限、版本冲突、通知重试与审计。CI 配置相同测试和构建；远程结果以 GitHub Actions 中对应提交的检查为准。

## 本地运行

需要 Java 21、Node 22.12+，首次运行需下载依赖，Maven 无需预装。隔离演示无需系统 PostgreSQL 或 Docker：

```sh
./scripts/run-local-demo.sh
# 另开终端：
cd apps/web
npm ci
npm run dev
```

脚本会提示为本次演示设置 `admin` 密码，不回显；也可通过 `BOOTSTRAP_ADMIN_PASSWORD` 环境变量注入。打开 [本地工作台](http://127.0.0.1:5173)。演示数据库随进程结束清理，样例数据与正式业务数据分离。持久化开发环境见 [环境说明](infra/README.md)。

## M1 隔离查询演示

M1已有独立只读HTTP与座位查询页，尚不开放预约提交。Java21/Node就绪后，在仓库根运行`bash scripts/check-m1-http.sh`复验，`bash scripts/start-m1-http-demo.sh`启动独立18080/18081/5174环境；密码在终端临时设置，账号student。不会替换原D演示。详情和人工验收步骤见 [HTTP验证记录](docs/process/m1-http-query-validation.md) 与 [API契约](contracts/m1-query-api.md)。

## M1-A 普通个人预约与旧演示

普通应用个人预约入口已新增：`/seat-reservations`，可通过明确安全配置在生产jar启用个人提交；默认关闭写入。当前按用户选择自然日窗口，M2整室/维修尚未接入，尚非全系统放行。复验`bash scripts/check-m1-personal.sh`；预览`bash scripts/start-m1-personal-preview.sh`，使用[::1]:5374隔离旧演示会话。详见[普通入口配置/API](contracts/m1-personal-api.md)和[验证/人工步骤](docs/process/m1-personal-runtime-validation.md)。

以下为旧隔离演示入口，其写入关闭描述仅指当时/对应配置；当前普通入口以上文为准。使用`bash scripts/check-m1-submit.sh`复验，`bash scripts/start-m1-reservation-demo.sh`启动独立18180/18181/5274环境。浏览器用localhost:5274，账号student、终端临时密码；本轮只演示个人预约，未包含M2整教室/维修限制，生产入口仍关闭。见[分工边界](docs/requirements/m1-a-boundary.md)和[验证/操作步骤](docs/process/m1-reservation-submit-validation.md)。

本人历史/再次预约位于`/my-reservations`，复用原座位后必须重新填写时段并重新验证；普通环境可按上述配置新建，旧演示保持独立。运行`bash scripts/check-m1-history.sh`复验本轮157后端/46前端/28只读及48提交网络检查；旧Java演示需按步骤重启才能加载新接口，临时数据库会清理，已加载新版的当前演示无需重复重启。脚本、指定页面观察及日期＋整点下拉已获用户确认，详见[历史验证与人工操作](docs/process/m1-history-validation.md)及[接口契约](contracts/m1-history-api.md)。

## M1-B CF-M1-007 每日教室码与签到

学生在`/my-reservations`的“已预约”记录中输入 6 位当日教室码签到。按 2026-10-30 用户最新决定，当前体验阶段不限制签到时间；服务端只允许本人把`CONFIRMED`原子更新为`CHECKED_IN`。正确码会进入`/seat-usage/:reservationId`使用状态页，由服务端签到时刻驱动计时；学生二次确认退座后原子更新为`COMPLETED`并停止计时。错误码清空后可重新输入，数据库错误次数限制仍保留。空间管理员从“空间资源”的启用教室详情打开`/room-checkin-display/:roomId`，可查看按上海日期轮换的教室码和倒计时。CF-M1-008～011仍未实现。

普通入口额外要求独立的`M1_CHECKIN_SECRET`。复验继续使用`bash scripts/check-m1-personal.sh`；人工页面验收使用`bash scripts/start-m1-personal-preview.sh`。详见[签到/使用状态API契约](contracts/m1-check-in-api.md)和[验证记录](docs/process/m1-check-in-validation.md)。历史自动化结果不代表最新调整、远程CI、提交或部署已经通过。

签到人工预览必须使用`start-m1-personal-preview.sh`及其`http://[::1]:5374`地址；`run-local-demo.sh`只启动D公共层，单独配合5173前端不会启动M1。预览会创建`student`与`manager`，并为`student`准备一条A101预约，便于完成“管理员取码—学生签到—计时—退座”检查。

**不同身份必须使用不同浏览器环境。** `manager`与`student`不能只在同一浏览器配置下打开两个标签页，因为同源标签页共享`CF_SESSION` Cookie，后登录的账号会替换前一个账号。需要同时查看两个身份时，请使用两个不同浏览器（例如 Edge 与 Chrome），或一个普通窗口和一个 InPrivate/无痕窗口；也可以在同一窗口退出`manager`后再登录`student`顺序验收。

## 关键文档

- [M1-A阶段交付与文档索引](docs/handoffs/m1-a-delivery.md) · [续做手册](docs/handoffs/m1-a-resume.md)（当前状态、按负责人交接、延期项及上传安排）

- [架构决策：单仓库与服务边界](docs/architecture/adr-0001-repository-and-services.md)
- [M2 需求与待决规则](docs/requirements/m2.md)
- [M1 需求评审稿](docs/requirements/m1.md) · [并发方案 B](docs/architecture/adr-0003-m1-concurrency.md) · [评审包](docs/process/m1-review.md)
- [M1 核心规则与验证](docs/process/m1-core-validation.md) · [M1-B 接入说明书](docs/handoffs/m1-b.md)（随 A 实现更新，A 完成后交接）
- [CF-M1-007 签到契约](contracts/m1-check-in-api.md) · [签到验证记录](docs/process/m1-check-in-validation.md)
- [M1 内部座位查询方案](docs/architecture/m1-availability-plan.md) · [80测试与查询复验](docs/process/m1-availability-validation.md)（完整D/M2/M3集成仍待进行）
- [D三项补充实际验证](docs/process/m1-d-supplement-validation.md) · [D/M1-B/M2/M3负责人说明书](docs/handoffs/m1-integration.md)（M1 86及前端11已有用户复验，D24由同次本地报告补证；未部署）
- [D 实现范围](docs/requirements/d.md) · [D 接口契约](contracts/platform-api.md)
- [本次验证与验收记录](docs/process/d-validation.md)
- [风险与工程伦理](docs/requirements/risks-and-ethics.md)
- [GitHub 配置与发布清单](docs/process/github-setup.md)
- [首次提交验收清单](docs/process/bootstrap-review.md)

未选择开源许可证；公开可见不代表已授予开源许可。后续由组内决定许可证。
