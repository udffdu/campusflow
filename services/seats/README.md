# M1 自习座位与资源占用

M1-A 负责人：`leafwhisper286`；M1-B 同学账号、模块总负责人及实际评审人待登记。A 负责查询、预约、历史复用及统一占用协调；B 负责签到、提醒、违约与运营查询。各自完成前后端与测试，交叉评审。
向 M2 提供整教室占用与释放，向 M3 提供维修限制的写入与解除。
统一控制个人预约、整教室占用、维修限制的并发与有效性；不自行维护第二份空间主数据。

已实现预约时间和状态核心、个人预约/历史、真实 D 会话 HTTP，以及 CF-M1-007 每日教室码、本人签到、使用计时、主动退座和教室显示页；M2/M3接入、提醒、未签到违约、自动完成、运营查询及完整业务尚未实现。需求与已确认局部规则单独登记，不能视为全组批准的完整基线：

- [需求与验收计划](../../docs/requirements/m1.md)
- [机器可读决策](../../spec/m1.json)
- [并发方案 B](../../docs/architecture/adr-0003-m1-concurrency.md)
- [成员评审清单](../../docs/process/m1-review.md)
- [给M1-B的交接说明](../../docs/handoffs/m1-b.md)（按用户要求，A完成后再交接）
- [核心规则实际验证](../../docs/process/m1-core-validation.md)
- [预约存储设计](../../docs/architecture/m1-storage.md) · [存储与事务锁实际验证](../../docs/process/m1-storage-validation.md)
- [内部查询方案](../../docs/architecture/m1-availability-plan.md) · [查询与80测试验证/人工复验步骤](../../docs/process/m1-availability-validation.md)
- [D补充/权限与插座验证](../../docs/process/m1-d-supplement-validation.md) · [各负责人接入说明书](../../docs/handoffs/m1-integration.md)
- [签到接口契约](../../contracts/m1-check-in-api.md) · [CF-M1-007验证记录](../../docs/process/m1-check-in-validation.md)

配置好Java21后，在仓库根目录运行：

```sh
./services/platform/mvnw -B -f services/seats/pom.xml verify
```

当前复用仓库已有Maven Wrapper；该命令构建seats并启动独立临时PostgreSQL测试，不启动或修改D。迁移由Flyway管理在m1 schema内；测试不能用root运行。2026-10-30 的177项本地测试是修改签到时间策略和增加使用计时/主动退座之前的历史结果，不能作为最新代码已复验的证据；最新版本按用户要求先完成人工页面确认，提交前仍需另行决定是否重跑。M2/M3占用、提醒、未签到违约、自动完成和运营查询尚未完成。
