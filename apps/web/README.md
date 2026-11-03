# Vue 3 前端

已实现登录、工作台、空间维护、账号/角色/院系、通知中心、投递队列与审计。使用 Vue 3 + TypeScript + Vue Router + Vite，依赖在 package-lock.json 锁定。M1/M2 业务页面不在本次范围。M3重建任务1B已加入报修及本人查询页面，默认接入关闭，尚未真实联调。

需要 Node 22.12+：`npm ci` → `npm run dev`。默认 [本地入口](http://127.0.0.1:5173)，`/api` 代理到本机 8080 的真实 D 服务。

`npm test` 运行 Vitest，`npm run build` 同时检查类型和生产构建。认证凭据使用 HttpOnly Cookie，写操作附 CSRF 令牌；不把会话/密码存入 localStorage。

具备权限导航、加载/错误/空态、分页、版本冲突反馈和窄屏导航。后端执行最终授权。正式静态构建由 Nginx 同源代理 API，内部接口不经网页入口暴露。

M3重建验收记录见 [任务1B](../../docs/process/M3/m3-rebuild-task1b-validation.md)。只有D/入口负责人确认代理及独立CSRF并验证路由后才能设置 VITE_M3_INTEGRATION_APPROVED=true；当前不得仅打开开关。原D登录和CSRF保持不变。

重建任务1新增受权管理列表与详情，见 [验收记录](../../docs/process/M3/m3-rebuild-management-task1-validation.md)。任务1当时不含候选、派单和历史展示；当前候选进度见下方任务2说明。

重建任务2在受权待派单管理详情加入候选搜索/分页/选择，当前D适配默认503，不产生派单操作。见 [任务2验收](../../docs/process/M3/m3-rebuild-candidates-task2-validation.md)。

重建任务3已加入首次派单面板、冲突刷新和原请求重试。生产候选/资格仍待D接入，入口开关不启用；见 [任务3验收](../../docs/process/M3/m3-rebuild-dispatch-task3-validation.md)。

重建任务4在本人/管理详情共用WorkOrderHistory，显示真实操作ID、状态与上海时间，无编辑入口；历史随详情权限及加载/错误状态清除。36项前端测试和构建通过，真实入口仍未开放；见 [任务4验收](../../docs/process/M3/m3-rebuild-history-task4-validation.md)。
