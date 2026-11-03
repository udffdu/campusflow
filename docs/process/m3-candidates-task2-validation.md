# M3任务2：维修人员选择验收包

2026-11-02。授权依据：用户“继续完成任务2，给D留下接口，其他按照要求完成，同时维护之前的D需要做到的内容的清单”。此为推进任务2的明确授权，不补造任务1真实D联调通过记录。

关联 CF-M3-003。已实现 M3 内部部分，真实候选联调未完成，不宣称任务2全部验收通过。

## 修改和设计

新增 CandidateProvider（类型化D适配口）、PendingCandidateProvider（生产依赖未就绪明确503）、CandidatesApi（授权、范围、待派单状态、分页搜索、响应完整性校验）。无D修改、无新依赖、无数据库变更、无伪造HTTP接口。
新增 CandidatePicker.vue 和3个组件测试；ManagementView详情嵌入选择器，只对具maintenance.dispatch且工单待派单者显示。显示人员名、启用状态、维修资格和服务院系；搜索及分页可遍历全量结果；每次查询、切换工单/用户/权限清除旧选择，过期响应不覆盖新结果。无人员或失败时没有可选项。选择仅通过组件select事件传出，未增加派单按钮/写操作，等待任务3接续并再次复核资格。
后端不信任页面选择；从M3工单取得范围，只有合法范围才调用provider。对返回的不合格人员整页拒绝，避免total和分页被静默改变。前端不携带服务密钥。

## 实际检查与证据

仓库根目录：
```powershell
$env:JAVA_HOME='D:/oracleJdk-27'
$env:TEMP='D:/CampusFlow-Tools/tmp'
$env:TMP=$env:TEMP
$mvn='C:/Users/fgrhy/.m2/wrapper/dists/apache-maven-3.9.11/03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc/bin/mvn.cmd'
& $mvn -o '-Dmaven.repo.local=D:/CampusFlow-Tools/m2/repository' '-DargLine=-Djava.io.tmpdir=D:/CampusFlow-Tools/tmp' -f services/maintenance/pom.xml verify
```
BUILD SUCCESS。日志 services/maintenance/target/candidates-verify.log 和 surefire-reports。新增 candidatesScopeEligibilityAndPagination：真实PG工单、D身份HTTP替身、人员provider测试替身，验证普通用户拒绝、越院系404、真实接口DTO/分页、停用/无资格/越服务范围503、真空列表、未接入错误及禁止自报departmentId；原测试保留。

apps/web：
```powershell
node node_modules/vitest/vitest.mjs run
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
node node_modules/vite/bin/vite.js build
```
24项前端测试全部通过，类型检查通过，Vite生产构建通过（67 modules）。新增测试检查人员选择事件、翻页/搜索参数、旧选择清除、依赖错误重试和空态、缺权限不查询。

## 独立验收与边界

1. 真实候选显示：未完成，D尚无接口和真实适配器。测试人员只存在测试代码，不能当演示数据。
2. 停用/无资格/越范围：M3内部测试通过；D真实过滤待联调。
3. 接口失败：可恢复错误、无伪造候选，通过内部测试。
4. 空候选：明确提示且无可选人员，通过内部测试；本轮没有派单提交功能。
5. 隐私：类型化返回仅白名单字段，不含密码/会话；真实D适配器需同样遵守。

## 人工步骤

现阶段：具相应权限/已完成入口评审的环境打开管理工单详情，候选查询应显示“维修人员服务尚未接入”；无权限不显示入口，直接GET仍拒绝。不能为演示擅自启用未评审代理或修改D权限。
D接入后：使用两院系的真实派单用户，进入待派单工单→搜索人员→翻页验证total及稳定顺序→选择→刷新或切换搜索确认旧选择清除；停用或撤销资格的人员不得成为有效候选；断开D后错误可重试。路径见 contracts/m3-candidate-port.md。

## 依赖、确认及后续

D需完成清单D-M3-01/03/05/06；D-M3-04资格复核留任务3。人员资格与范围编码是M3适配提案，D外部评审未完成。未启用代理，真实D登录/候选/浏览器链路未验证。首次派单、历史扩展及派单并发破坏测试留任务3/4，未提前实施。
建议提交：feat(m3): [CF-M3-003] 增加维修人员选择及D候选适配接口。
未提交、未推送，完成本轮后等待人工验收。
