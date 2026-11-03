# M3 → D 依赖清单（重建任务3首次派单）

2026-11-03；这是阶段状态，不代表D已确认或已交付。最终交接版已保存在安全提交588327b，任务4将结合重建成果完整纳入。

| 编号 | 需要D/相关负责人完成 | 当前阶段 |
| --- | --- | --- |
| D-M3-01 | 登记maintenance.report/read.own/read.scope/dispatch及维修资格映射，提供角色矩阵；不能用spaces.write代替 | 当前候选入口也要求dispatch；维修资格maintenance.repair映射待D确认 |
| D-M3-02 | 确认报修及管理范围、ADMIN与业务权限的关系 | 报修按真实院系二次授权；本人查询均只本人；管理要求read.scope且获权ADMIN全局、其他获权者本院系 |
| D-M3-03 | 候选查询HTTP契约、完整分页及服务范围 | CandidateProvider/API/页面已重建；生产默认503，D正式HTTP接口及M3真实适配待完成 |
| D-M3-04 | 指定人员当前资格复核及资格撤销竞态语义 | AssigneeEligibility/guard已接入派单；生产默认503/D_ELIGIBILITY_NOT_READY，D真实HTTP复核仍待 |
| D-M3-05 | 安全发放M3服务凭据、内部地址、TLS、超时及联调环境 | 待交付，不在文件填写秘密 |
| D-M3-06 | 与入口负责人评审同源M3优先路由及独立CSRF | 未启用代理，不修改D登录/CSRF |
| D-M3-07 | 确认审计动作和去重联调；通知收件人另行决定 | 投递worker已重建，默认关闭；处理创建/派单审计，真实D去重待验，无通知 |
| D-M3-08 | 可选的受权历史姓名解析 | 当前ID已符合Format，非1A阻塞项 |

已有D接口：POST /internal/v1/authorization、GET /internal/v1/resources/{id}，由服务端Bearer认证调用；不能直接访问D数据库。会话来自CF_SESSION且必须allowed=true。资格与候选不能用当前会话authorization替代。

正式接入前记录确认人/日期、契约版本、实现迁移、测试证据及联调环境。D自测、M3替身测试、真实联调分开记录，当前外部确认栏均待填。M3独立数据库专用角色和schema部署复核由部署/M3负责人完成，不默认转交D。

任务2当前适配数据见 [候选契约](../../contracts/m3-candidate-port.md)：请D确认方法/路径、服务认证、操作者上下文、搜索分页及范围语义，再提供真实实现和测试证据。不能用管理员全量用户接口或假候选替代。真实评审/联调仍待填。

任务3复核请求actorId/assigneeId/roomId/departmentId，要求响应同一人员/房间/院系且allowed明确true；D须确认HTTP契约、资格撤销竞态、MAINTENANCE.REPORTED/ASSIGNED审计语义及凭据。M3资格适配尚未实现真实HTTP，当前拒绝派单；审计调度M3_AUDIT_ENABLED默认false。详见 [重建任务3](../process/m3-rebuild-dispatch-task3-validation.md)。
