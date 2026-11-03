# M1 migration fixtures

仅测试使用，不进入生产 jar。V3–V5 原样取自 M1 提交 `9cdf49216dd95acd10124b7a6e7b44d958c13265`；V6 与 PR #3 的本轮邮件领取令牌迁移一致。测试将这些脚本与当前 M2 的真实生产 V1/V2/V2.1 组合，在临时 PostgreSQL 验证升级。M1 schema 后续变化时需更新快照并重新评审升级路径。

SHA-256：

- `V3__seat_booking_metadata.sql`: `9048024d5e3608415a4ee8bc801c257503d415682eb28cdf73114150899d7d35`
- `V4__email_notifications.sql`: `c846d814edb87b6599e0f2e7cbe84aee5300edf0ac3165cd76927a5d7d4f2aa3`
- `V5__seat_operations_permission.sql`: `28fb4dfebffc759413bfcfde65190c4bce7307c2e5c3c0e695966b3208d8d510`
- `V6__email_claim_token.sql`: `ea01fa45c5da29111b424a78ec85959b104af3d3f523cf3d5479dc79819f3e16`
