# CampusFlow · 校园资源协同平台

以自习座位预约为核心，连接整教室申请审批与物业报修的课程实践项目。

当前阶段：D 公共层与前端公共外壳已实现，用户于 2026-10-23 确认本版并授权提交推送，跨模块契约仍待组内评审。M1-A 已有部分内部实现及本地验证，完整 M1 和 M2/M3 业务尚未交付；尚未部署华为云。

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

## 关键文档

- [架构决策：单仓库与服务边界](docs/architecture/adr-0001-repository-and-services.md)
- [M2 需求与待决规则](docs/requirements/m2.md)
- [M1 需求评审稿](docs/requirements/m1.md) · [并发方案 B](docs/architecture/adr-0003-m1-concurrency.md) · [评审包](docs/process/m1-review.md)
- [M1 核心规则与验证](docs/process/m1-core-validation.md) · [M1-B 接入说明书](docs/handoffs/m1-b.md)（随 A 实现更新，A 完成后交接）
- [M1 内部座位查询方案](docs/architecture/m1-availability-plan.md) · [80测试与查询复验](docs/process/m1-availability-validation.md)（完整D/M2/M3集成仍待进行）
- [D三项补充实际验证](docs/process/m1-d-supplement-validation.md) · [D/M1-B/M2/M3负责人说明书](docs/handoffs/m1-integration.md)（M1 86及前端11已有用户复验，D24由同次本地报告补证；未部署）
- [D 实现范围](docs/requirements/d.md) · [D 接口契约](contracts/platform-api.md)
- [本次验证与验收记录](docs/process/d-validation.md)
- [风险与工程伦理](docs/requirements/risks-and-ethics.md)
- [GitHub 配置与发布清单](docs/process/github-setup.md)
- [首次提交验收清单](docs/process/bootstrap-review.md)

未选择开源许可证；公开可见不代表已授予开源许可。后续由组内决定许可证。
