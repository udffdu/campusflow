<script setup lang="ts">
import {onUnmounted,ref,watch} from 'vue'
import {useRouter} from 'vue-router'
import {ApiError,message} from '../api'
import {m1Get} from '../m1Api'
import {can,session} from '../session'
import {checkInReservation,pendingFor,validReservationHistoryItem,type Capabilities,type ReservationHistory,type ReservationHistoryItem} from '../reservations'
const router=useRouter();const page=ref<ReservationHistory>();const caps=ref<Capabilities>();const busy=ref(false);const error=ref('');const cursors=ref(['']);const index=ref(0);const hasPending=ref(false)
const states:Record<string,{label:string;icon:string;tone:string}>={CONFIRMED:{label:'待签到',icon:'◷',tone:'pending'},CHECKED_IN:{label:'签到成功 · 使用中',icon:'✓',tone:'success'},COMPLETED:{label:'签到成功 · 已完成',icon:'✓',tone:'success'},NO_SHOW:{label:'未签到违约',icon:'!',tone:'violation'},DISRUPTED:{label:'系统原因终止',icon:'–',tone:'disrupted'}}
const checkInCodes=ref<Record<string,string>>({});const checking=ref('');const checkInMessage=ref<Record<string,string>>({})
let sequence=0,alive=true
function date(value:string){return new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',dateStyle:'short',timeStyle:'short',hourCycle:'h23'}).format(new Date(value))}
function duration(seconds:number){const h=Math.floor(seconds/3600),m=Math.floor(seconds%3600/60),s=seconds%60;return `${String(h).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`}
async function load(target=0,reset=false){
  const user=session.user?.id;if(!user)return
  const seq=++sequence;busy.value=true;error.value='';page.value=undefined;caps.value=undefined;hasPending.value=!!pendingFor(user)
  const cursor=reset?'':cursors.value[target]??''
  try{
    const capability=await m1Get<Capabilities>('/reservation-capabilities')
    const data=await m1Get<ReservationHistory>(`/reservations?size=20${cursor?'&cursor='+encodeURIComponent(cursor):''}`)
    if(!Array.isArray(data.items)||!data.items.every(validReservationHistoryItem)||typeof data.nextCursor!=='string'||!Number.isFinite(Date.parse(data.serverTime)))throw new Error('预约列表响应不完整，请刷新重试')
    if(alive&&seq===sequence&&session.user?.id===user){caps.value=capability;page.value=data;if(reset)cursors.value=[''];index.value=target;if(data.nextCursor)cursors.value[target+1]=data.nextCursor;else cursors.value.length=target+1}
  }catch(e){if(alive&&seq===sequence&&session.user?.id===user)error.value=message(e)}finally{if(alive&&seq===sequence)busy.value=false}
}
async function repeat(id:string){
  if(session.user&&pendingFor(session.user.id)){hasPending.value=true;error.value='请先到预约页面恢复尚未核实的提交结果';return}
  await router.push({path:caps.value?.canCreateReservation?'/seat-reservations':'/seat-reservation-demo',query:{repeatReservationId:id}})
}
async function checkIn(id:string){
  checking.value=id;checkInMessage.value={...checkInMessage.value,[id]:''}
  try{const updated=await checkInReservation(id,checkInCodes.value[id]??'');if(page.value){const history:ReservationHistoryItem={...updated,completedAt:updated.completedAt,usageDurationSeconds:Math.max(0,Math.floor((Date.parse(updated.serverTime)-Date.parse(updated.checkedInAt))/1000)),violationReason:null};page.value={...page.value,serverTime:updated.serverTime,items:page.value.items.map(item=>item.id===id?history:item)}};checkInCodes.value={...checkInCodes.value,[id]:''};await router.push(`/seat-usage/${encodeURIComponent(id)}`)}
  catch(e){if(e instanceof ApiError&&e.code==='CHECK_IN_CODE_INVALID')checkInCodes.value={...checkInCodes.value,[id]:''};checkInMessage.value={...checkInMessage.value,[id]:e instanceof ApiError&&e.code==='CHECK_IN_CODE_INVALID'?'签到码错误，请重新输入':message(e)}}finally{checking.value=''}
}
watch(()=>session.user?.id,()=>{sequence++;page.value=undefined;caps.value=undefined;cursors.value=[''];index.value=0;error.value='';busy.value=false;hasPending.value=false;checkInCodes.value={};checkInMessage.value={};checking.value='';void load(0,true)},{immediate:true})
onUnmounted(()=>{alive=false;sequence++})
</script>
<template>
  <div class="page-heading"><div><div class="eyebrow">M1 / MY RESERVATIONS</div><h1>我的预约</h1><p>查看自己的预约记录；再次预约时需要重新选择时段。</p></div><button class="button secondary" :disabled="busy" @click="load(0,true)">刷新列表</button></div>
  <p v-if="hasPending" role="status" class="query-notice">还有尚未核实的提交。请先回到 <RouterLink :to="caps?.canCreateReservation?'/seat-reservations':'/seat-reservation-demo'">预约页面</RouterLink> 恢复原请求。</p>
  <p v-if="busy" role="status">正在读取本人预约…</p>
  <p v-if="error" role="alert" class="form-error">{{error}} <button class="text-link" :disabled="busy" @click="load(index)">重试</button></p>
  <section v-if="page" class="panel history-panel" aria-live="polite">
    <div class="history-heading"><div><span class="section-kicker">RESERVATION TIMELINE</span><h2>预约历史</h2><p>状态与使用时长均来自服务端记录；使用中的时长截至本次刷新。</p></div><div class="history-legend" aria-label="状态颜色说明"><span><i class="legend-dot success"/>签到成功</span><span><i class="legend-dot violation"/>未签到违约</span></div></div>
    <p v-if="!page.items.length" role="status">当前没有本人的预约记录。</p>
    <article v-for="item in page.items" :key="item.id" class="history-card" :class="`history-${states[item.status].tone}`">
      <div class="panel-heading"><h3>{{date(item.startAt)}} — {{date(item.endAt)}}（上海）</h3><span class="status-badge" :class="states[item.status].tone"><b aria-hidden="true">{{states[item.status].icon}}</b>{{states[item.status].label}}</span></div>
      <p class="mono">预约编号：{{item.id}}</p><details><summary>查看资源编号</summary><p>教室：{{item.roomId}}</p><p>座位：{{item.seatId}}</p><p class="muted">当前教室、座位名称将在再次预约时读取。</p></details>
      <dl v-if="item.checkedInAt" class="history-usage"><div><dt>签到时间</dt><dd>{{date(item.checkedInAt)}}（上海）</dd></div><div><dt>{{item.status==='CHECKED_IN'?'已使用时长':'本次使用时长'}}</dt><dd class="duration-value">{{duration(item.usageDurationSeconds)}}</dd></div><div v-if="item.completedAt"><dt>结束时间</dt><dd>{{date(item.completedAt)}}（上海）</dd></div></dl>
      <form v-if="item.status==='CONFIRMED'" class="check-in-form" @submit.prevent="checkIn(item.id)"><label :for="`check-in-${item.id}`">教室当日签到码</label><div><input :id="`check-in-${item.id}`" v-model="checkInCodes[item.id]" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" placeholder="6 位数字" required :disabled="checking===item.id"><button class="button" :disabled="!!checking">{{checking===item.id?'正在签到…':'签到'}}</button></div><small>请最晚在预约开始后 15 分钟内完成签到，以服务端时间为准。</small><p v-if="checkInMessage[item.id]" class="form-error" role="alert">{{checkInMessage[item.id]}}</p></form>
      <div v-else-if="item.status==='CHECKED_IN'" class="usage-entry"><p class="success-note" role="status">座位正在使用中。</p><RouterLink class="button" :to="`/seat-usage/${item.id}`">进入使用状态</RouterLink></div>
      <div v-else-if="item.status==='NO_SHOW'" class="no-show-note" role="status"><strong>未签到违约</strong><p>预约开始后 15 分钟内未签到，系统已取消预约、释放座位，并记录一次未签到违约。</p></div>
      <button v-if="(caps?.canCreateReservation||caps?.canCreateDemoReservation)&&can('seats.availability.read')" class="button secondary" :disabled="hasPending" @click="repeat(item.id)">再次预约</button>
      <p v-else class="muted">本环境暂未开放预约创建，或当前账号没有所需权限。</p>
    </article>
    <nav v-if="page.items.length||index" class="history-pages" aria-label="本人预约翻页"><button class="button secondary" :disabled="!index||busy" @click="load(index-1)">上一页</button><span>第 {{index+1}} 页</span><button class="button secondary" :disabled="!page.nextCursor||busy" @click="load(index+1)">下一页</button></nav>
  </section>
</template>
<style scoped>.history-panel{padding:24px}.history-heading{display:flex;justify-content:space-between;gap:24px;padding-bottom:22px;border-bottom:1px solid #e4e9e1}.history-heading h2{margin:4px 0;font-size:24px}.history-heading p{margin:0;color:#788675}.section-kicker{font-size:10px;letter-spacing:.18em;color:#6e8a70}.history-legend{display:flex;align-items:center;gap:16px;white-space:nowrap;color:#718071;font-size:12px}.history-legend span{display:flex;align-items:center;gap:6px}.legend-dot{width:9px;height:9px;border-radius:50%;background:#87958a}.legend-dot.success{background:#3f8a5c}.legend-dot.violation{background:#bd5b43}.history-card{position:relative;margin-top:16px;padding:20px;border:1px solid #e0e7de;border-left:5px solid #97a696;border-radius:12px;background:#fff}.history-card.history-success{border-left-color:#3f8a5c;background:linear-gradient(90deg,#f2faf3 0,#fff 24%)}.history-card.history-violation{border-left-color:#bd5b43;background:linear-gradient(90deg,#fff4ef 0,#fff 24%)}.history-card.history-pending{border-left-color:#5d8197}.history-card.history-disrupted{border-left-color:#a68854}.history-card h3{font-size:16px;margin:0}.history-card p{overflow-wrap:anywhere;line-height:1.8}.history-card details{margin:14px 0}.status-badge{display:inline-flex;align-items:center;gap:7px;padding:6px 11px;border-radius:999px;font-size:12px;font-weight:600;background:#edf1ec;color:#667367}.status-badge b{display:grid;place-items:center;width:17px;height:17px;border-radius:50%;background:#fff}.status-badge.success{background:#def1e2;color:#286842}.status-badge.violation{background:#f8dfd5;color:#963f2e}.status-badge.pending{background:#e3eef4;color:#3e6b81}.status-badge.disrupted{background:#f2e9d8;color:#7a6132}.history-usage{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:14px 0;padding:15px;background:#f2f7f1;border-radius:10px}.history-usage div{min-width:0}.history-usage dt{font-size:11px;color:#738272}.history-usage dd{margin:5px 0 0;overflow-wrap:anywhere}.duration-value{font:600 16px/1.4 Consolas,monospace;color:#246244}.history-pages{display:flex;align-items:center;justify-content:flex-end;gap:14px;padding-top:20px;flex-wrap:wrap}.query-notice{padding:18px;background:#edf4f0;border-radius:12px}.check-in-form{display:grid;gap:8px;max-width:430px;margin:16px 0}.check-in-form label{font-size:12px;color:#6c805d}.check-in-form>div{display:flex;gap:9px}.check-in-form input{width:150px;letter-spacing:3px}.check-in-form small{color:#8a9584;line-height:1.7}.check-in-form .form-error,.check-in-form .success-note{margin:2px 0 0}.no-show-note{max-width:660px;padding:13px 15px;border:1px solid #efccb9;border-radius:10px;background:#fff4ed;color:#8e402f}.no-show-note p{margin:3px 0 0}@media(max-width:760px){.history-heading{align-items:flex-start;flex-direction:column}.history-usage{grid-template-columns:1fr}.history-legend{white-space:normal}}@media(max-width:650px){.history-panel{padding:18px}.history-card{padding:16px}.history-card .panel-heading{align-items:flex-start;flex-direction:column;gap:12px}}</style>
