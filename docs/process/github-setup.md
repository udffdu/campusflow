# GitHub 组织、仓库与发布配置

目标组织：udffdu。目标仓库名：campusflow。用户已允许公开。
这是配置清单，不代表已在 GitHub 生效；执行后填写实际状态与证据链接。

## 组织与仓库

- 两位组织 Owner，其余成员使用本人账号；名单待提供，不主动邀请未知账号。
- 三个模块团队，D 另指定备用评审人；普通开发者使用 Write 权限。
- 一个主仓库，默认分支 main。允许普通合并提交，建议关闭 rebase/squash 合并以保留已验收的小提交。
- 启用 Issues，使用需求、问题模板；不开设不必要的长期分支。
- .github/CODEOWNERS.example 是未生效示例；真实账号确认后建立 CODEOWNERS。

## main 保护目标

- 仅经 PR 合并，至少一位其他成员批准，新提交后重新评审。
- 必须通过 `Repository checks / repository-checks`，并解决全部评审讨论。
- 保护规则适用于管理员，禁止强推、删除。
- 业务代码出现后增加真实构建与测试门禁，仓库结构检查不能替代业务测试。
- D、契约、schema 变更需受影响模块确认；CODEOWNERS 同一路径多个 owner 默认任一批准即可，不能冒充双方批准。

## CI 与发布

当前工作流仅检查结构和需求登记，使用只读权限，不访问云凭据、不执行部署。
未来 PR 运行构建测试，main 合并成功后自动部署整套测试环境。
生产发布锁定已验证的提交和镜像摘要，人工批准后部署；记录 tag、提交、制品、审批人与回退目标。
按环境设置部署并发锁；迁移、种子、集成测试和回退演示需实际执行。
不把普通 workflow_dispatch 手动按钮等同于独立发布审批。

## 套餐核查依据

公开仓库可以使用免费组织的分支保护、CODEOWNERS 和环境审批；私有仓库存在套餐限制。
个人 Pro 不等同于组织 Team；私有 Team 仓库的分支保护与生产 required reviewers 不是同一项权益。

- [分支保护](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)
- [CODEOWNERS](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners)
- [环境与审批](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)

## 远程操作状态

- 公开仓库列表已核对，组织当前无公开仓库；本地 origin 已指向拟创建地址，但不代表远程仓库存在。
- GitHub 插件连接已核实：账号 Dianashiba，已授权组织 udffdu。插件提供内容操作，创建仓库与分支保护通过网页管理。
- 首次推送、保护规则、团队权限、真实 CODEOWNERS：待执行。
- 服务器与生产环境审批：未配置。
