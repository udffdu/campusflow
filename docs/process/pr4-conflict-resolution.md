# PR #4 与 main 冲突整合

日期：2026-11-03。用户明确要求“解决一下目前pr的冲突”，授权本次解决冲突、提交并推送；不合并 PR 或部署。

检查时 PR #3 已合并，唯一开放的 PR 为 #4。本次将 main `c529a21f71166dc567738df70de9bf26f97a308e` 合入 M2 分支，以合并提交保留双方历史，不重写提交、不强推。原工作目录未提交的 README 修改保留，本次在独立 worktree 中进行。

## 13 个冲突文件的处理

- 前端保留 M1 全部查询、预约、签到、历史、运营路由及 M2 申请路由、权限导航；开发代理同时保留 M1、M2 和 D。
- 通知中心保留 M1 可读提醒格式和 M2 申请详情链接；角色名称和内置角色保护取双方完整集合。
- D 同时保留 V1/V2/V2.1/V3–V6 共 7 个迁移，原文件与校验和不变；调整集成后的升级数量断言，保留 V2.1 兼容策略与邮件领取令牌修复。
- CI 同时运行 metadata、platform-tests、seats-core-tests、m1-http-network、rooms-tests、web-tests，聚合门禁要求全部成功。
- 需求登记按稳定编号整合：保留所有 M1/M2 条目；main 原先误挂在 CF-M2-001 下的 M1 运营实现和验证证据归回 CF-M1-011，没有删除证据或弱化验收条件。
- 协作说明、README 和契约入口同步双方现状，继续明确真实占用/M3/SMTP/部署未验收。

## 验证

Java 21.0.13，真实临时 PostgreSQL 17.11；前端使用锁定依赖。

| 检查 | 结果 |
| --- | --- |
| D `./mvnw -B clean verify` | 41 项通过，构建成功 |
| M1 `./services/platform/mvnw -B -f services/seats/pom.xml clean verify` | 206 项通过，构建成功 |
| M2 `./mvnw -B clean verify` | 29 项通过，使用当前整合的真实 D，构建成功 |
| web `npm ci && npm test && npm run build` | 80 项通过，类型检查和生产构建通过 |
| `python3 scripts/check_repository.py` | 4 个模块、28 项需求登记通过 |
| 冲突标记与 `git diff --check` | 通过 |
| M1 真实 HTTP 查询 / 提交 / 普通 jar 检查 | 分别 28 / 49 / 58 项通过，独立 D/M1 进程与 PostgreSQL |

新增测试覆盖双方内置角色都不可篡改，以及同一通知中心同时展示 M1 提醒和正确编码的 M2 详情链接；M2 前端身份夹具补齐 D 新增的可空 email 字段。

HTTP 检查使用 `bash scripts/prepare-m1-http.sh -q` 准备 classpath，然后执行 `check_m1_http.py`、`check_m1_submit.py`、`check_m1_personal.py`。Mac 自带 Bash 在无位置参数时因脚本的 nounset 设置退出，添加正常 Maven `-q` 参数后完成准备；未改生产规则或测试断言。

本地检查不等同于远程 CI；以本合并提交在 PR Checks 中的实际结果为准。PR 保持未合并，不变更 main。
