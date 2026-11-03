下面作为 **M3 工单统一格式 v1**。双方后续开发按此对齐，不受当前实现限制。

**派单后的工单示例**

```json
{
  "id": "工单UUID",
  "reporterId": "报修人UUID",
  "roomId": "房间UUID",
  "departmentId": "房间所属院系UUID",
  "faultType": "LIGHTING",
  "description": "教室照明灯无法正常点亮",
  "status": "ASSIGNED",
  "assigneeId": "维修人员UUID",
  "dispatcherId": "派单人UUID",
  "createdAt": "2026-11-02T08:00:00Z",
  "assignedAt": "2026-11-02T09:00:00Z",
  "updatedAt": "2026-11-02T09:00:00Z",
  "version": 2,
  "history": [
    {
      "id": "历史记录UUID",
      "action": "CREATED",
      "actorId": "报修人UUID",
      "fromStatus": null,
      "toStatus": "PENDING_DISPATCH",
      "assigneeId": null,
      "remark": null,
      "occurredAt": "2026-11-02T08:00:00Z"
    },
    {
      "id": "历史记录UUID",
      "action": "ASSIGNED",
      "actorId": "派单人UUID",
      "fromStatus": "PENDING_DISPATCH",
      "toStatus": "ASSIGNED",
      "assigneeId": "维修人员UUID",
      "remark": null,
      "occurredAt": "2026-11-02T09:00:00Z"
    }
  ]
}
```

**工单字段定义**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | UUID | 工单唯一编号，由服务端生成；后续操作均使用它 |
| `reporterId` | UUID | 报修人 ID，由服务端验证登录身份后确定 |
| `roomId` | UUID | D 中的房间 ID；维修联动以此定位房间 |
| `departmentId` | UUID | 报修时房间所属院系，从 D 获取，用于权限范围判断 |
| `faultType` | 枚举 | `LIGHTING` 照明、`POWER` 供电、`FURNITURE` 家具、`HVAC` 空调、`OTHER` 其他 |
| `description` | 字符串 | 故障描述，去首尾空白后 5–1000 个 Unicode 码点，按纯文本处理 |
| `status` | 枚举 | 工单当前状态，见下表 |
| `assigneeId` | UUID / `null` | 当前维修人员；未派单时为空 |
| `dispatcherId` | UUID / `null` | 实际派单人；未派单时为空 |
| `createdAt` | 时间 | 工单创建时间 |
| `assignedAt` | 时间 / `null` | 首次派单成功时间；未派单时为空 |
| `updatedAt` | 时间 | 最近一次成功业务变更时间 |
| `version` | 整数 | 工单版本，创建为 `1`，每次成功业务变更加 `1`；用于并发控制 |
| `history` | 数组 | 操作历史；详情返回，列表不返回 |

**统一状态与交接位置**

| 状态 | 含义 | 负责阶段 |
|---|---|---|
| `PENDING_DISPATCH` | 待派单 | A |
| `ASSIGNED` | 已派单、等待维修 | A |
| `IN_PROGRESS` | 维修中 | B |
| `PENDING_ACCEPTANCE` | 已提交完工，待验收 | B |
| `COMPLETED` | 验收通过，工单完成 | B |

流程规定为：

`PENDING_DISPATCH → ASSIGNED → IN_PROGRESS → PENDING_ACCEPTANCE → COMPLETED`

验收不通过：`PENDING_ACCEPTANCE → IN_PROGRESS`。超时升级单独记录，不改变主状态；评价不改变主状态。

**历史字段定义**

| 字段 | 说明 |
|---|---|
| `id` | 历史记录唯一 UUID |
| `action` | 操作类型：`CREATED`、`ASSIGNED`、`REPAIR_STARTED`、`COMPLETION_SUBMITTED`、`ACCEPTANCE_PASSED`、`ACCEPTANCE_REJECTED` |
| `actorId` | 本次真实操作者，由服务端确定 |
| `fromStatus` | 操作前状态；创建时为 `null` |
| `toStatus` | 操作后状态 |
| `assigneeId` | 操作发生时的维修人员 ID，保留当时事实 |
| `remark` | 操作说明，无说明时为 `null`；完工说明和验收驳回原因必填 |
| `occurredAt` | 本次操作发生时间 |

**双方必须遵守的交接规则**

1. **已派单工单必须具备** `assigneeId`、`dispatcherId`、`assignedAt`，并有一条 `ASSIGNED` 历史。
2. 后续修改提交 `expectedVersion`；服务端核对当前 `version`，不一致返回 `409`。成功重试不得再次加版本或追加历史。
3. 工单修改、对应历史和必要待发送事件在同一个本地事务中保存。
4. 所有时间由服务端生成，统一使用 UTC ISO-8601；页面按上海时间显示。历史按 `occurredAt`、`id` 升序排列。
5. ID 使用 D 的真实标识，不把姓名当关联键。前端不能指定真实操作者或任意修改状态。
6. **上报、派单不改变座位可用性。**在维修阶段通过 M1 公开接口处理限制，以工单 `id` 作为来源标识，不能直接修改 M1 数据库。
7. 此格式是 M3 内部完整详情格式；对外接口仍按查看权限裁剪字段。双方共用一套工单模型，不各建一套工单表。