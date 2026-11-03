# M2 整教室申请与审批

负责人：dianashiba。独立 Spring Boot 服务，默认 8082，拥有申请、审批、历史、超时、取消与恢复记录；不拥有真实占用。前端路径 `/room-applications`。

- [业务规则与范围](../../docs/requirements/m2.md)
- [浏览器 API 与 D 权限](../../contracts/rooms-api.md)
- [M1 占用接口待评审](../../contracts/m2-occupancy-api.md)
- [验证与待验收记录](../../docs/process/m2-validation.md)

## 测试

Java 21。M2 测试启动真实 PostgreSQL 17.11 与独立 D 进程，因此先打包 D：

```sh
cd ../platform
./mvnw verify
cd ../rooms
./mvnw verify
```

测试不依赖安装系统 PostgreSQL/Docker。M1 使用仅在 src/test 的 HTTP 协议夹具，包含响应丢失、错误回执、冲突与版本墓碑；不将其当成真实 M1 验收。测试读取 D 已构建 jar，不共享 D Java 实体或数据库业务表。

## 启动

从仓库根运行 `./scripts/run-m2-demo.sh`，按提示设置本次演示密码，再单独运行前端 `npm run dev`。账号 admin、applicant、reviewer、approver 使用同一临时密码，仅供本机虚构数据演示。数据库进程退出即清理；不要用于真实用户。演示启动 D:8080 与 M2:8082，端口须空闲。

**默认不模拟 M1。** 可以提交、初审、终审、驳回、撤销，终审后保持“占用处理中”；取消已进入占用处理的申请保持“释放确认中”，直到真实 M1 按契约回应。测试用 ACTIVE 回执不会出现在生产 jar 或默认演示流程中。

持久化启动使用独立 PostgreSQL 数据库与 ROOMS_DATABASE_URL/USER/PASSWORD，Flyway 建立 m2 schema；不能授予其 D/M1 业务表权限。环境参数：

| 变量 | 意义 |
| --- | --- |
| ROOMS_PORT | 默认 8082 |
| PLATFORM_URL | 默认 http://127.0.0.1:8080 |
| M2_SERVICE_KEY | D 的 M2 调用密钥；空值拒绝受保护请求 |
| M1_URL | 默认 http://127.0.0.1:8081，M1 未接入时处理任务持续等待 |
| M2_OCCUPANCY_KEY | M2→M1 独立密钥 |
| COOKIE_SECURE | 默认 true，本地 HTTP 显式 false |
| ROOMS_APPROVAL_HOURS | 默认 48，范围 1～168 |
| ROOMS_MAX_HOURS | 默认 8，范围 1～16 |
| ROOMS_ADVANCE_DAYS | 默认 30，范围 1～365 |

每次恢复任务最多处理 10 条占用、20 条事件，SQL SKIP LOCKED 防止多个实例同时处理同条记录；HTTP 调用有界超时，故障持续可重试。管理员界面的任务详情仅暴露错误码与次数，不含 Cookie、密钥或远程正文。
