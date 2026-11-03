# M3 维修人员候选适配约定 v1（留给 D 评审）

2026-11-02：用户明确授权任务2“给D留下接口，其他按照要求完成”。本文定义 M3 需要的数据，不宣称 D 已评审或已实现。未擅自创建 D HTTP 路径。

M3 浏览器入口：GET /api/v1/maintenance/management/work-orders/{id}/candidates?q=&page=0&size=20。
先通过 D 验证 CF_SESSION 和 maintenance.dispatch allowed=true，再从 M3 工单读取 roomId/departmentId，按操作者范围限制访问，限 PENDING_DISPATCH。禁止客户端提交 actorId/departmentId；page 0–100000、size 1–100、q 最多100码点。

M3 留出的 Java 接口：CandidateProvider.search(actorId, roomId, departmentId, query, page, size)。前三个参数由服务端确定，不能直接转发浏览器自报值。D HTTP 路径、方法、服务认证和操作者上下文验证方式等待 D 评审；确认后通过实现 CandidateProvider 接入，替换 PendingCandidateProvider，不把两者同时注册为歧义Bean。

返回结构：
```json
{"items":[{"id":"人员UUID","displayName":"显示名","active":true,"qualification":"maintenance.repair","globalScope":false,"departmentIds":["院系UUID"]}],"page":0,"size":20,"total":1}
```

- id：D真实人员UUID；displayName：必要显示名，不传联系方式。
- active：必须为true；qualification：有效维修资格，约定 maintenance.repair，D需确认映射。
- globalScope：是否具有明确的全局服务范围；否则 departmentIds 必须包含目标院系。房间级例外范围如有，需要先扩展契约，不能偷偷缩减为院系模型。
- total：对目标房间/范围和搜索条件过滤后的完整候选数；page/size原样回传，页面须填满至最后一页，稳定按显示名及id排序。不允许仅返回前N条冒充全部。
- 范围或资格错误、重复id、分页数量不一致：M3返回503/D_INVALID_CANDIDATES，不静默过滤、截断或改total。并发目录变更导致不一致时重新加载。
- 只允许白名单DTO进入前端，不得透传D任意JSON，尤其密码、令牌、密钥。

当前生产 PendingCandidateProvider 返回503/D_CANDIDATES_NOT_READY，区别于真实空列表200/total=0。没有伪造候选。测试中才替换provider。

当前任务3/4已完成内部实现与验收：首次派单调用AssigneeEligibility独立复核指定assigneeId的启用/资格/范围；生产实现仍为PendingAssigneeEligibility，返回503，不存在已接通的D资格HTTP接口。候选选中不是资格承诺；复核与本地提交间的竞态仍待双方确认。成功派单的原键原内容重放不重新复核人员、不新增派单，操作者当前授权和工单范围仍重新校验。完整当前交接见 [D功能清单](../docs/requirements/m3-d-capability-requests.md)。

分页补充：超过末页可返回items=[]但保留真实total；只有过滤后无候选才total=0。displayName非空白，departmentIds为非null数组，资格编码/角色映射由D确认后通过M3适配器转换。q先按原输入100码点上限校验，再去首尾空白传给provider。
