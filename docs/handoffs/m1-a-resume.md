# M1-A 续做手册

暂停点：2026-10-29，普通个人预约脚本和三项指定页面已由用户确认；跨模块接入留后补。先读[阶段交付索引](m1-a-delivery.md)，本文件提供恢复工作所需的最小步骤。

## 仓库与基线

- 正式开发目录：`/Users/lily/Documents/Codex/2026-10-22/qin/work/campusflow`；旧`campusflow-snapshot`仅保留历史演示，不是Git开发目录。
- 分支：`feat/CF-M1-002-personal-reservation`；远程：`https://github.com/udffdu/campusflow.git`。
- 功能验收基线4e5608f，21个自有本地提交。最近实现：33e4a27后端/真实jar网络、91ed835前端、4e5608f文档。收尾文档已保存为5232648，此22提交基线已上传且远程CI通过；此后分支重命名的链接同步另保存一个仅文档提交，最新版本以`git log`及远程对应CI为准。
- 继续时先读仓库`AGENTS.md`、本索引和`spec/m1.json`；检查`git status`与远程分支，不重置他人修改、不自动解合并冲突。

## 运行与检查

本机外层便捷脚本（使用现有Java/cache，不纳入项目Git）：

```sh
bash "/Users/lily/Documents/Codex/2026-10-22/qin/scripts/start-m1-personal-preview.sh"
# 需要验证新的实现变更时，再执行：
bash "/Users/lily/Documents/Codex/2026-10-22/qin/scripts/check-m1-personal.sh"
```

可移植入口均在仓库内：Java21、Node22.12+、Python3及依赖下载就绪后，在仓库根运行`bash scripts/start-m1-personal-preview.sh`或`bash scripts/check-m1-personal.sh`。无需复制外层缓存或本机绝对路径；嵌入式PostgreSQL需本机监听权限，缺少网络/依赖时按实际错误处理，不跳过数据库测试。

普通预览默认D18280/M118281/web5374，浏览器使用`http://[::1]:5374/seat-reservations`，账号student，临时密码由终端设置不回显。保留IPv6主机以隔离旧localhost/127.0.0.1演示Cookie。预览为普通生产jar＋临时PG，退出清理；不要将它的临时数据当作持续业务记录。实际外部持久PG的运行配置见[普通契约](../../contracts/m1-personal-api.md)，正常应用退出不会删除外部数据。

旧只读5174、旧预约演示5274和当前5374可能仍在用户终端运行，恢复时先检查端口，不能重复启动或擅自停止。需要结束某个临时预览时，在对应终端Ctrl+C；不要批量结束Java/Node进程。当前没有要求本次关闭这些演示。

最近已通过：M1164、web50/类型/构建，真实网络只读28、旧演示48、普通jar53；新4+共享安全7项破坏实验检测预期失败。用户脚本和指定三项页面通过，详情见[验收](../process/m1-personal-runtime-validation.md)。文档整理不改变源码，不因此重跑同一批应用测试。后续改变代码时按影响范围验证。

## 从哪里继续

| 工作 | 开始前读什么 | 条件/范围 |
| --- | --- | --- |
| M1侧统一占用/来源 | [来源方案](../architecture/m1-external-occupancy-plan.md)、[评审清单](../process/m1-external-occupancy-review.md) | 目前仅草案；接口/服务授权/拟V2迁移经受影响方评审后实现；同时接入候选与创建，不代写M2/M3业务 |
| B后续接入 | [B说明书](m1-b.md) | 五状态和锁已定，尚无通用状态更新/违约/派发器；用户在A完成后安排交接 |
| D边界补充 | [各负责人说明书](m1-integration.md) | 午夜表示、停用竞态继续单列；在线读取不保证跨服务原子性 |
| 工程收尾 | [需求登记](../../spec/requirements.json)、[评审汇总](../process/m1-review.md)、[CI配置](../../.github/workflows/repository-checks.yml) | 推送后查看真实CI；PR评审/全组发布与回退仍待，不合并或部署未经批准的版本 |

共享数据：M1自己的V1三表，D此前新增V3，既有迁移不可改写。普通写入默认关闭；配置CALENDAR_DAYS、1天窗口、4小时时长及独立CSRF秘密后启用。不要回退成旧滚动24小时建议，也不要把PENDING事件当作已发通知。所有新预约相关写入须遵守现有用户/教室锁协议和版本规则，保留历史及幂等记录。

## 上传前后的状态

用户于2026-10-29回复“确认、上传”，授权保存收尾材料并上传。上传目标是现有功能分支，保持提交历史，不强推、不合并main。目标[GitHub分支](https://github.com/udffdu/campusflow/tree/feat/CF-M1-002-personal-reservation)，原22个提交已核对远程SHA一致、CI成功；后续每次推送仍核对远程提交SHA与[CI真实结果](https://github.com/udffdu/campusflow/actions)；如需PR评审，另按确认范围创建/完善PR并附验收与未完成项。尚无自动部署证据，不把CI构建等同部署。
