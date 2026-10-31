# CampusFlow · 校园资源协同平台

以自习座位预约为核心，连接整教室申请审批与物业报修的课程实践项目。

当前阶段：D 公共层与前端公共外壳已实现；M1-A 个人预约及 M1-B 的 CF-M1-007～011 已完成本地实现并具备隔离验证入口。M1-B 覆盖每日教室码、本人签到、使用计时与退座、预约提醒、违约终结与通知、可视化历史，以及院系范围内的预约运营统计。真实 SMTP 服务商验收、D 负责人评审、M2/M3 业务及完整跨模块联调仍未完成；尚未部署华为云。

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
source scripts/resolve-python.sh
"$PYTHON" scripts/check_repository.py
```

Shell 脚本会自动选择可用的 Python 3.11+：Linux/macOS 通常为`python3`，Windows Git Bash 通常为`python`。需要手动指定时设置`CF_PYTHON_EXECUTABLE`为解释器路径。

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

本人历史/再次预约位于`/my-reservations`，复用原座位后必须重新填写时段并重新验证；普通环境可按上述配置新建，旧演示保持独立。历史接口在同一次服务端快照中返回签到/完成时刻、使用时长和违约原因；页面以文字、图标和颜色共同区分待签到、签到成功/使用中、签到成功/已完成与未签到违约，不能只依赖颜色表达状态。运行`bash scripts/check-m1-history.sh`复验历史基础流程；CF-M1-010 的最新历史展示另见[未签到生命周期验证](docs/process/m1-no-show-validation.md)。旧Java演示需按步骤重启才能加载新接口，临时数据库会清理。详见[历史验证与人工操作](docs/process/m1-history-validation.md)及[接口契约](contracts/m1-history-api.md)。

## M1-B 签到、提醒、违约与运营

M1-B 的 CF-M1-007～011 已形成完整本地流程：学生使用每日教室码签到，进入按服务端时间计时的使用状态并可主动退座；系统在预约开始前 15 分钟和开始后 10 分钟仍未签到时生成站内信与邮件提醒；开始后 15 分钟仍未签到的预约原子转为`NO_SHOW`，释放个人座位占用、记录一次违约并保留可视化历史；授权运营人员可按时间、教室、座位和状态查看本院系预约，读取签到率、违约数、实际利用率及资源排行。状态转换沿用统一用户/教室事务锁，提醒和违约事实可去重，运营接口只读且不返回学生身份。

运营读取使用独立的`seats.operations.read`权限，不会自动授予既有管理员或普通用户；授权范围来自有效 D 会话和院系资源，越范围请求失败关闭。M1 只通过 D 的公开契约获取身份、资源和通知能力，不读取 D 业务表。M2 整室占用与 M3 维修来源仍未接入，真实 SMTP 服务商送达、D 负责人评审、远程 CI 和部署也不在当前本地验收结论内。

在仓库根目录依次执行以下隔离检查，可覆盖整个 M1-B。各脚本使用独立临时数据库和测试数据，不修改正常开发数据库；`tmp/`不应提交：

```sh
bash scripts/check-m1-personal.sh
bash scripts/check-m1-reminders.sh
bash scripts/check-m1-no-show.sh
bash scripts/check-m1-operations.sh
```

人工页面验收和单项测试细节请查阅对应文档：

- CF-M1-007：[签到与使用状态验证](docs/process/m1-check-in-validation.md)；
- CF-M1-008/009：[预约提醒验证](docs/process/m1-reminder-validation.md)；
- CF-M1-010：[违约生命周期验证](docs/process/m1-no-show-validation.md)；
- CF-M1-011：[预约运营验证](docs/process/m1-operations-validation.md)。

涉及`manager`、`student`、`operator`等不同身份时，必须使用不同浏览器、不同浏览器配置，或普通窗口与无痕窗口；同源标签页共享`CF_SESSION` Cookie，后登录的账号会替换前一个账号。API、权限、统计口径和环境配置分别见[签到契约](contracts/m1-check-in-api.md)、[提醒契约](contracts/m1-reminder-api.md)与[运营查询契约](contracts/m1-operations-api.md)。

## 关键文档

- [M1-A阶段交付与文档索引](docs/handoffs/m1-a-delivery.md) · [续做手册](docs/handoffs/m1-a-resume.md)（当前状态、按负责人交接、延期项及上传安排）

- [架构决策：单仓库与服务边界](docs/architecture/adr-0001-repository-and-services.md)
- [M2 需求与待决规则](docs/requirements/m2.md)
- [M1 需求评审稿](docs/requirements/m1.md) · [并发方案 B](docs/architecture/adr-0003-m1-concurrency.md) · [评审包](docs/process/m1-review.md)
- [M1 核心规则与验证](docs/process/m1-core-validation.md) · [M1-B 实现范围与协作边界](docs/requirements/m1-b.md) · [M1-B 接入说明书](docs/handoffs/m1-b.md)
- [M1 内部座位查询方案](docs/architecture/m1-availability-plan.md) · [80测试与查询复验](docs/process/m1-availability-validation.md)（完整D/M2/M3集成仍待进行）
- [D三项补充实际验证](docs/process/m1-d-supplement-validation.md) · [D/M1-B/M2/M3负责人说明书](docs/handoffs/m1-integration.md)（M1 86及前端11已有用户复验，D24由同次本地报告补证；未部署）
- [D 实现范围](docs/requirements/d.md) · [D 接口契约](contracts/platform-api.md)
- [本次验证与验收记录](docs/process/d-validation.md)
- [风险与工程伦理](docs/requirements/risks-and-ethics.md)
- [GitHub 配置与发布清单](docs/process/github-setup.md)
- [首次提交验收清单](docs/process/bootstrap-review.md)

未选择开源许可证；公开可见不代表已授予开源许可。后续由组内决定许可证。
