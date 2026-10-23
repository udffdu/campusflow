# Vue 3 前端

已实现登录、工作台、空间维护、账号/角色/院系、通知中心、投递队列与审计。使用 Vue 3 + TypeScript + Vue Router + Vite，依赖在 package-lock.json 锁定。M1/M2/M3 业务页面不在本次范围。

需要 Node 22.12+：`npm ci` → `npm run dev`。默认 [本地入口](http://127.0.0.1:5173)，`/api` 代理到本机 8080 的真实 D 服务。

`npm test` 运行 Vitest，`npm run build` 同时检查类型和生产构建。认证凭据使用 HttpOnly Cookie，写操作附 CSRF 令牌；不把会话/密码存入 localStorage。

具备权限导航、加载/错误/空态、分页、版本冲突反馈和窄屏导航。后端执行最终授权。正式静态构建由 Nginx 同源代理 API，内部接口不经网页入口暴露。
