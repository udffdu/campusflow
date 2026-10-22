# CampusFlow · 校园资源协同平台

以自习座位预约为核心，连接整教室申请审批与物业报修的课程实践项目。

当前阶段：项目启动与接口讨论。尚无业务实现、可运行应用或云端部署；目录和 CI 的存在不代表业务已经完成。

## 已确认的上下文

- 团队 6 人，模块已分工；`dianashiba` 负责 M2 和公共层 D。
- 技术讨论基线：Spring Boot + Vue 3 + PostgreSQL；具体版本和依赖尚未锁定。
- GitHub 组织：`udffdu`；项目名称：CampusFlow；拟使用仓库名：`campusflow`。
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

当前检查只验证仓库约定，不包含应用构建、业务测试或部署。应用代码加入时必须同步补充相应检查。

## 关键文档

- [架构决策：单仓库与服务边界](docs/architecture/adr-0001-repository-and-services.md)
- [M2 需求与待决规则](docs/requirements/m2.md)
- [风险与工程伦理](docs/requirements/risks-and-ethics.md)
- [GitHub 配置与发布清单](docs/process/github-setup.md)
- [首次提交验收清单](docs/process/bootstrap-review.md)

未选择开源许可证；公开可见不代表已授予开源许可。后续由组内决定许可证。
