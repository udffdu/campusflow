# 给 D、M1-B、M2、M3 负责人的接入说明书

更新：2026-10-28。范围：本次预约权限、教室预约开放范围、座位插座标记补充。持续更新，尚未转发；A整体未完成。本稿记录实际实现，不是其他负责人已接收或同意的证明。

## 1. 决策与当前交付范围

用户leafwhisper286在阅读D现有契约及补充建议后明确要求：“补充内容你直接增加即可，只需要在最后生成一份说明书告知其他部分的负责人。”本批据此直接实现三项及必要的迁移、界面与M1内部规则，免去这三项实施前联系D负责人的前提；未把授权扩大到M2/M3新业务或其他未决政策。

已在本地实现：D权限目录/角色、V3迁移、资源API新增范围、资源管理表单的范围与插座控件、M1内部范围/权限计算及插座筛选。D负责人仍按仓库登记为dianashiba，但没有登记其本人已经评审同意。历史核查见 [接入建议及来源](../process/m1-d-integration-review.md)。

尚未实现：M1 HTTP服务、D到M1的真实网络适配、完整预约页面、创建事务前全部条件组合、M2/M3网络链路、签到/违约、事件派发。不能把本批扩展宣称为完整M1交付。

## 2. D负责人需要了解的权限与角色

新增权限目录（global_only=false）：

| 权限 | 语义 |
| --- | --- |
| seats.availability.read | 查询本人可预约的候选座位 |
| seats.reservations.create | 创建本人预约 |
| seats.reservations.read.own | 查询本人单条/历史，不含他人运营记录 |

新增受保护的内置角色SEAT_USER，包含上述三项；可以另建自定义角色选择其中一部分。现有ADMIN、MEMBER和全部已有用户均不自动增加权限。管理员应在用户已有角色集合上显式附加SEAT_USER；角色分配沿用现有账号管理界面/API，既有会话下一次请求即按新权限判定。

只赋SEAT_USER不会附带spaces.read/write。需要浏览D目录的普通预约用户可同时保留MEMBER；无需赋予空间管理权限。ADMIN也必须显式得到相应座位权限，read-own不升级为读他人记录或代约能力。SEAT_USER本身不可通过自定义角色修改接口覆盖，角色分配可撤销。

继续沿用`POST /internal/v1/authorization`及原请求结构。权限名称符合既有`[a-z.]{1,64}`校验，无需新增鉴权接口。D原有对管理院系的ADMIN例外保持原样；M1个人预约还有下述业务范围校验，不能只依赖allowed结果跳过它。

## 3. D资源API的新增字段与兼容方式

ROOM新增bookingScope，响应值为DEPARTMENT或CAMPUS；其他资源类型响应null，不接受非空设置。座位使用所属教室的范围。

| 场景 | 规则 |
| --- | --- |
| 新建ROOM省略/null | 默认DEPARTMENT |
| 更新ROOM省略/null | 保留当前范围，旧页面更新不会重置CAMPUS |
| 显式DEPARTMENT | 预约用户须有业务权限且本人院系与教室管理院系相同 |
| 显式CAMPUS | 允许有业务权限的跨院系用户；仍检查开放、有效性及占用 |
| 修改范围 | 继续要求spaces.write和原有管理院系范围，校验expectedVersion并递增room.version |

`departmentId`继续表示管理归属，不随全校开放而清空或改变；CAMPUS不授予外院系成员编辑资源的权利。M1个人预约不因ADMIN角色而豁免DEPARTMENT范围。

现有`POST /api/v1/resources`的创建体可增设bookingScope；更新仍发送其余必需字段，例如：

```json
{
  "name": "A101 教室",
  "capacity": 40,
  "features": ["投影仪"],
  "openingHours": [{"day": 3, "start": "09:00", "end": "18:00"}],
  "active": true,
  "expectedVersion": 7,
  "bookingScope": "CAMPUS"
}
```

示例为虚构数据，expectedVersion应使用当前读取值。内部资源列表/单条也返回新增字段。服务调用方应兼容新增响应字段，并按契约核查资源类型；实际值以D返回为准。

## 4. 插座标记

SEAT.features中的精确字符串POWER_OUTLET表示该座位已确认有插座；管理界面使用“有插座”勾选框，详情显示中文。可与“靠窗”等其他属性共存，取消勾选只删除标准标记，保留其他属性。

ROOM等其他类型不能设置POWER_OUTLET。教室“电源插座”及座位历史“电源”等自由文本继续保留，不自动转换、不从教室继承到每个座位；未带标准标记的座位不匹配“有插座”筛选。需要转换实际主数据时，由资源维护者核实后通过资源API操作，不能手工批量改库。

## 5. 数据库迁移与发布边界

D新增`V3__seat_booking_metadata.sql`，V1/V2未改写：

1. 新增三项权限、SEAT_USER角色及角色权限关系，不写已有用户的user_roles。
2. resources新增booking_scope；既有ROOM回填DEPARTMENT并更新版本/时间，其他类型保持null。
3. 约束只允许ROOM使用两个合法非空值；保留null，兼容旧D二进制未带新列的ROOM插入。新D将这类ROOM的null解释为DEPARTMENT，绝不解释为全校开放。
4. 不转换历史features，不改变M1的m1 schema，也不接触M2/M3表。

已测试空库V1→V3、真实V2库升级到V3、Flyway重跑不重放已执行迁移、旧字段插入和旧更新请求。发布时先迁移D数据库并部署支持新字段/角色的D，再启用新前端和M1适配；当前没有部署或改动运行中的演示数据库。

迁移为扩展式，可保留数据库结构；这不是完整应用回退验证。旧二进制不认识新范围和SEAT_USER保护，不能回退后继续把它当作完整新业务服务；生产发布/回退顺序与验证仍需在对应阶段落实。

## 6. M1-A/B 接入边界

内部`ReservationAccessPolicy.scopeFor(identity,action,room)`支持QUERY和CREATE权限/范围计算。identity为未来D适配层提供的可信输入，不是会话验证器，不能接收前端自报身份。DEPARTMENT比较本人院系，CAMPUS仍检查动作权限。

内部`SeatAvailabilityQuery.queryForIdentity(identity,room,seats,period,requiresPower)`组合上述规则及原有有效性、完整开放、个人占用判断；requiresPower=true只保留带POWER_OUTLET的座位。原AccessScope查询入口是供已验证范围使用的内部入口，不能绑定为浏览器载荷。

原RoomSnapshot/SeatSnapshot构造器保留兼容形式，分别默认DEPARTMENT及空features。未来真实适配必须把D的bookingScope和features明确映射进来，不能因使用旧构造器而漏掉CAMPUS或插座信息。未识别的范围须拒绝，不得默认全校开放。

CREATE业务范围方法已提供，但尚未装配到完整HTTP创建流程；ReservationStore.create仍只是内部存储事务。未来应用层须在线核实身份、权限、主数据、开放及所有占用，不能把仅存储成功当作完整授权成功。

B继续使用 [M1-B说明书](m1-b.md) 的状态/锁协议。上述三项权限不代表签到、违约、运营查询权限已经定义；相关B功能仍按后续实际实现补齐，A完成后由用户统一交接。

## 7. M2/M3 与公共前端负责人

- M2：bookingScope只定义个人座位预约范围，不自动决定整教室申请资格或审批范围。管理归属ID未改，既有M2草案路径未改；冲突保留个人预约的对接仍待对应阶段落实。
- M3：维修限制仍由具体工单来源管理，不通过改bookingScope或插座属性解除维修；本批没有新增维修接口。
- 公共前端：Space类型增加可选bookingScope以兼容旧响应；资源界面新增范围选择与插座勾选，SEAT_USER名称和权限说明可见。新页面写入字段需要新D版本，当前用户运行的snapshot页面没有被切换或重启。

## 8. 验证、用户复验与未决项

AI本地结果：D真实PG/HTTP层测试24项，M1测试86项，前端11项，全部通过；前端类型检查和构建通过。前端测试模拟API传输，D测试走MockMvc及真实数据库，不能据此宣称完成浏览器到独立M1/D的真实网络联调。

12项隔离破坏实验全部检测到预期断言失败，0执行错误，原始Java源码未变。用户随后提供16:05:32完成的M1 86测试及前端11测试/构建成功输出；AI从16:05:27生成的同次D报告核对24测试通过，用户消息未贴D段日志。详见 [验证及复验记录](../process/m1-d-supplement-validation.md)。据此分步保存本地提交；远程CI、PR评审、部署仍未完成，不能视为完整M1验收。

本机统一复验：

```sh
bash "/Users/lily/Documents/Codex/2026-10-22/qin/scripts/check-d-supplement.sh"
```

源码移到其他环境时，在Java21和前端依赖就绪后，使用仓库Maven Wrapper分别verify platform/seats，再在apps/web运行npm test和npm run build。外层本机脚本不属于项目Git，也不修改系统Java或Shell配置。

停用对在途/已有预约的生效边界、午夜开放表示、M1 HTTP路由/CSRF、配置归属、最终通知渠道及M2/M3契约仍未决定；没有因为这三项授权而自动批准。本说明会随实际接入继续更新，最终由用户转交相关负责人，不代发消息或伪造回执。

## 9. 只读HTTP阶段新增交接（脚本和三项页面已由用户复验）

用户已确认 [只读HTTP方案](../architecture/m1-http-query-plan.md)，现已实现 [M1查询API](../../contracts/m1-query-api.md) 和Vue /seat-query。浏览器仍向D登录；M1每次以M1服务凭据调用D authorization/resources，自己判断seats.availability.read与DEPARTMENT/CAMPUS，不共享D表或实体。D现有公开契约和生产源码未改；新增的D HttpFixture仅测试classpath，不进生产jar。

前端/api/m1优先转独立M1，/api/v1仍转D。只有SEAT_USER而无spaces.read的用户也能查询并登录到座位页；MEMBER本身只有D目录权限，不能代替SEAT_USER。没有自动给旧用户赋权，演示账号通过公开D API显式建立。

接口候选始终canReserve=false；M2/M3不得把此浏览器接口当作整教室占用承诺。D故障/服务凭据错误为503，不导致学生退出；会话失效401、无权限403、正常空200分开。D分页/必需字段异常不返回部分结果，范围/启停不缓存。

AI本地D24/M1123/前端22、真实TCP28及浏览器通过，六项破坏实验均检测到预期失败。用户脚本复验及三项页面观察已通过，Network人工观察未报告，远程CI/PR/部署尚未发生；旧D演示保留。独立启动、参数、测试证据和限制见 [验证记录](../process/m1-http-query-validation.md)。写入CSRF、配置归属、午夜、停用竞态、M2/M3和B功能仍待对应阶段，本说明继续累积，A完成后由用户转发，不代发。

2026-10-28 17:29更新：用户已完成只读HTTP脚本复验，真实TCP28及登记通过；D24/M1123有同次本地报告补证。页面人工操作仍待完成，本批未提交/发送，证据分类见 [复验记录](../process/m1-http-query-validation.md)。

后续页面确认：用户对A101四座、插座筛后一座、只读提示/无提交按钮回复“一切正常”。已记录该三项人工验收，按流程本地保存本批；Network专项观察及其他负责人评审不冒充已完成，说明书仍未发送。

下一阶段 [预约提交方案](../architecture/m1-reservation-submit-plan.md)尚待用户确认，非已实现：拟仅在新隔离演示中创建个人预约和恢复本人请求，M1自己验证写入保护，不修改D公开CSRF/鉴权契约。正式开启、D停用竞态与M2/M3依然待对应评审，不因本稿而扩大先前三项补充授权。

## 10. M1-A个人预约隔离阶段（本轮脚本及三项页面验收已完成）

重读任务书后明确[A/B/M2/M3边界](../requirements/m1-a-boundary.md)。仅实现M1-A个人提交、重放/请求恢复、本人单条读取。D公开接口/Java源码/schema不改，M1独立校验自己的CSRF/Origin并按既有三项权限使用D；普通M1写入口仍关闭，测试配置不进入生产jar。M2审批、M3工单、B签到提醒违约运营均未代做，M1侧外部占用与来源协议仍待后续评审实现。

当前本地M1141、前端33、旧只读TCP28、新提交TCP32通过，七项隔离破坏实验均检测预期断言失败。D24沿用前一批复验证据；本批真实浏览器/用户页面和远程CI/部署未记为通过。详见[提交契约](../../contracts/m1-reservation-demo-api.md)及[验证记录](../process/m1-reservation-submit-validation.md)。没有把本轮当正式可预约能力或滚动24小时/停用补偿政策批准，说明书未发送。
