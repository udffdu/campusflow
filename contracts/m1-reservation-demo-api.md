# M1-A 隔离个人预约接口

2026-10-28。基于用户要求重读任务书、明确仅负责M1-A后继续下一步的指令实现。仅本地隔离个人预约阶段，尚待本批用户复验，不是正式可预约服务；M2/M3业务及B签到/提醒/违约/运营未实现。分工依据见 [M1-A边界](../docs/requirements/m1-a-boundary.md)。

## 启用条件

普通SeatsApplication保持写入关闭；即使前端直接POST也405。演示路由由测试classpath中的DemoFixtureConfiguration显式装配，生产jar不含该配置/HttpFixture，不能仅靠生产环境变量打开。该配置创建DemoWritePermit并验证实际绑定为127.0.0.1或::1、配置Origin为明确回环来源、独立32字节以上CSRF密钥（不得与服务凭据相同）。M1不更改D原有CSRF策略。

专用脚本创建独立临时PG、D18180/M118181/Vite5274；浏览器使用`http://localhost:5274`，与原`127.0.0.1:5174`按主机隔离Cookie（端口本身不隔离Cookie）。Origin为该前端源，不根据Host/转发头自动放行；测试/演示凭据仅进程环境注入，不进浏览器bundle或日志。普通GET查询仍canReserve=false。

## 接口

| 接口 | 权限与结果 |
| --- | --- |
| GET `/api/m1/v1/reservation-capabilities` | D验证的有效身份；返回stage、canCreateDemoReservation、canReadOwn、productionReady=false。普通环境stage=CANDIDATE_ONLY且不能演示提交 |
| GET `/api/m1/v1/csrf` | 只在隔离装配中存在；D在线验证seats.reservations.create，返回token及headerName=X-M1-CSRF-Token，no-store |
| POST `/api/m1/v1/reservations` | 只在隔离装配中存在；Origin与会话绑定CSRF，D在线验证创建权限。必需单个Idempotency-Key；JSON严格仅roomId、seatId、startAt、endAt |
| GET `/api/m1/v1/reservations/{id}` | D在线验证seats.reservations.read.own；只读本人；他人/缺失同404 |
| GET `/api/m1/v1/reservation-requests/{key}` | 同本人读取权限；按本人+请求键恢复已提交记录。404只表示当前没查到已提交记录，可能仍在处理中 |

读取接口在普通应用也可用，但不产生预约。查询、创建、本人读取权限独立；capabilities不代替后端鉴权。只有创建权限可POST但不自动获得单独GET读取权；POST成功响应可展示本次结果，随后的GET403不能把成功改成失败。

创建JSON起止为以Z结尾的UTC ISO时间，按上海整点/当前服务器时钟校验。所有未知/重复字段、身份、状态、来源和资源版本注入拒绝400。Idempotency-Key允许`[A-Za-z0-9._:-]{1,128}`，浏览器自动产生UUID。载荷上限4096字节，仅application/json；超长413、其他内容类型415。

新建201，重放200并`replayed=true`。结果字段id、roomId、seatId、startAt、endAt、status、version、resourceVersion、stage=PERSONAL_DEMO、productionReady=false；创建响应另有replayed。不泄露其他用户或会话信息。CONFIRMED仅表示演示个人预约已保存，不代表整教室/维修校验完成或提醒已发出。

## 写入验证与事务

M1令牌为HMAC-SHA256保护的用途/会话/有效期/随机nonce，15分钟有效，绑定当前CF_SESSION，不包含原始会话。头X-M1-CSRF-Token与精确Origin必需；拒绝缺失/null/错误Origin、明确跨站Fetch-Metadata、篡改/过期/跨会话令牌。失败403（重复头为非法输入400），不进预约写事务。签名密钥只由隔离进程注入，不能复用服务凭据。

进入事务前取得D可信用户；REQUIRES_NEW/READ_COMMITTED内按原用户/教室有序锁协议取得锁，再在线核验创建权限。先查旧请求：同用户+键+意图返回原记录当前状态，不重复审核新建条件；同键换意图409。仅新请求重新读取D ROOM/SEAT，核查类型/父子/院系、个人预约范围、启停、完整开放和正资源版本；最终按当前时钟校验整点/窗口/时长并重查个人占用，预约+请求+PENDING事件三表原子提交。

相同意图包含用户、教室、座位、时段，不包含D版本；D版本变化不使原请求变成新意图。重放仍需有效当前身份及创建权，但不因开始时间已过/资源后来停用/已被本人占用而重新创建。若创建权限撤销，POST403；仍有本人读取权可GET恢复。个人查询不是提交凭证，HTTP不会裸调缺少D校验的内部create。

新增ReservationStore.createValidated提供锁内鉴权和仅新建的受控验证回调；findForRequest只按本人读取。原create保留供已有核心/存储测试与隔离夹具使用，不是未鉴权HTTP入口。没有新增迁移或改写原V1，没有读取D表。

## 错误、恢复与限制

400输入/时间；401失效会话；403权限/范围/CSRF/Origin；404缺失/非本人；405关闭的写入口；409用户或座位冲突、同键不同意图、资源停用/开放冲突；503上游/预算/数据库/事务故障；500其他未预期异常。安全错误体含code/message/requestId，no-store，不回堆栈或凭据。D的服务凭据错误401/403映射M1的503，不使浏览器退出。

浏览器发请求前在按当前用户隔离的sessionStorage保存意图和请求键；超时/503/不完整响应保留同键，刷新恢复时先GET本人请求；404不自动换键。可确定的新建业务/时间冲突才释放草稿重新选择。未核实前锁住条件编辑，重试仍是同一请求；成功后的读取失败不重发新预约。退出/会话失效/换账号清草稿，不存密码、会话、CSRF或服务密钥。清理存储/换设备后的完整找回依赖后续历史列表，本轮不承诺。

插座是查询筛选，创建意图只承诺指定座位/时段；选择前重新查询一次，最终创建仍实时读取D，但不能将属性变更竞态当作已解决。M1事务不能锁住D的停用操作；最后一次D响应与提交之间仍有跨服务间隙。正式政策/补偿、M2/M3占用、完整历史/再次预约和B功能仍待对应阶段。

验证与人工操作见 [本批验证记录](../docs/process/m1-reservation-submit-validation.md)。
