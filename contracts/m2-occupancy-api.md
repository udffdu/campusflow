# M2 → M1 整教室占用契约

状态：M2 调用端已实现，M1 端待负责人评审和实现。不得把测试夹具或本文当成真实 M1 实现/全组一致记录。

## 请求

只走服务网络；生产 TLS。M2 使用独立 `M2_OCCUPANCY_KEY` 作为 Bearer；M1 根据凭据推导 source=M2，拒绝其他服务冒用。不要复用 M2→D 的 M2_SERVICE_KEY。M2 从配置 M1_URL 获得地址，浏览器不能指定。

`PUT /internal/v1/room-occupancies/{applicationId}`：

```json
{
  "roomId": "00000000-0000-0000-0000-000000000042",
  "startAt": "2026-11-10T02:00:00Z",
  "endAt": "2026-11-10T04:00:00Z",
  "desiredState": "ACTIVE",
  "operationVersion": 1,
  "traceId": "application-id-1"
}
```

释放使用同一 applicationId、教室、时间，desiredState=RELEASED，operationVersion=2。每次新的目标状态增加版本；同一次重试保持原版本和载荷。时段半开 `[startAt,endAt)`。

`GET /internal/v1/room-occupancies/{applicationId}` 返回已持久化的最高版本回执；404 只能表示从未收到该来源，不代表接口未实现。认证错误401/403，参数400，幂等载荷不符409，临时错误5xx。

成功 PUT/GET 的 JSON：

```json
{
  "applicationId": "00000000-0000-0000-0000-000000000001",
  "roomId": "00000000-0000-0000-0000-000000000042",
  "startAt": "2026-11-10T02:00:00Z",
  "endAt": "2026-11-10T04:00:00Z",
  "operationVersion": 1,
  "state": "ACTIVE",
  "reasonCode": null
}
```

state 仅 ACTIVE、RELEASED、CONFLICT。确定资源冲突也需持久化后以 200 和 CONFLICT 返回，可用 reasonCode=RESERVATION_CONFLICT/MAINTENANCE_BLOCK/RESOURCE_DISABLED。M2 不把不带可核对回执的 409 当作确定资源冲突；它会继续查询恢复。

## M1 必须保证

1. 同一房间的个人预约、M2 整室占用和 M3 维修限制共用原子互斥协议；包含已有个人预约和教室内所有座位，不能仅查空闲再写入。
2. 只释放 source=M2 + applicationId 对应记录，不清除 M1/M3 或另一申请的占用。
3. 同一版本相同载荷返回原结果，载荷不同拒绝；旧版本不可生效，可返回更高版本回执供调用方发现异常。
4. 即使先收到 RELEASED(2) 再收到 ACTIVE(1)，也始终为 RELEASED；保留 tombstone。M2 已取消的来源编号不会复用。
5. GET 回执是持久化结果。网络超时、服务重启和重复调用不得产生第二份占用；冲突结果同样稳定。
6. 申请前、最终占用前读取 D 的基础有效性、容量和时段；D 停用/时段调整与占用之间的并发规则需全组决定。M2 的预检或资源版本不构成分布式锁。

## M2 恢复方式

审批在本地事务中设置 PROCESSING/operationVersion=1。恢复任务在有锁的申请行上先 GET：未找到或版本较旧则 PUT；匹配最高版本和所有定位字段后确认结果。每个 HTTP 请求连接超时 2 秒、总超时 3 秒，不跟随重定向。网络调用期间持有申请行锁，用户撤销短暂等待；不会持有其他模块数据库锁。

处理超时或撤销进入 RELEASING，增加版本并发送 RELEASED。即使此前占用请求没有成功响应，也必须确认释放。响应丢失或本地提交失败时下轮 GET 恢复；每次最多退避 300 秒，五次后提示人工检查仍保留自动重试。错误鉴权、错误版本和不完整回执不能显示为批准或释放成功。

## 真实集成待验收

- M1 个人预约与 M2 占用并发，不能双重成功。
- 两个申请同房同段、同来源重复、回执丢失、先释放后创建、两个 M3 维修来源叠加。
- D 停用/更新开放时段与占用并发。
- M1 重启、回退及迁移后来源和操作版本仍保留。

自动测试对 M2 的调用/恢复使用 HTTP 夹具，只证明 M2 行为；不能替代以上 M1 真实验收。
