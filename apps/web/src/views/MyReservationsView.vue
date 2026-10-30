<script setup lang="ts">
import {onUnmounted,ref,watch} from 'vue'
import {useRouter} from 'vue-router'
import {ApiError,message} from '../api'
import {m1Get} from '../m1Api'
import {can,session} from '../session'
import {checkInReservation,pendingFor,validOwnReservation,type Capabilities,type ReservationHistory} from '../reservations'
const router=useRouter();const page=ref<ReservationHistory>();const caps=ref<Capabilities>();const busy=ref(false);const error=ref('');const cursors=ref(['']);const index=ref(0);const hasPending=ref(false)
const states:Record<string,string>={CONFIRMED:'已预约',CHECKED_IN:'使用中',COMPLETED:'已结束',NO_SHOW:'未签到取消',DISRUPTED:'系统原因终止'}
const checkInCodes=ref<Record<string,string>>({});const checking=ref('');const checkInMessage=ref<Record<string,string>>({})
let sequence=0,alive=true
function date(value:string){return new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',dateStyle:'short',timeStyle:'short',hourCycle:'h23'}).format(new Date(value))}
async function load(target=0,reset=false){
  const user=session.user?.id;if(!user)return
  const seq=++sequence;busy.value=true;error.value='';page.value=undefined;caps.value=undefined;hasPending.value=!!pendingFor(user)
  const cursor=reset?'':cursors.value[target]??''
  try{
    const capability=await m1Get<Capabilities>('/reservation-capabilities')
    const data=await m1Get<ReservationHistory>(`/reservations?size=20${cursor?'&cursor='+encodeURIComponent(cursor):''}`)
    if(!Array.isArray(data.items)||!data.items.every(validOwnReservation)||typeof data.nextCursor!=='string')throw new Error('预约列表响应不完整，请刷新重试')
    if(alive&&seq===sequence&&session.user?.id===user){caps.value=capability;page.value=data;if(reset)cursors.value=[''];index.value=target;if(data.nextCursor)cursors.value[target+1]=data.nextCursor;else cursors.value.length=target+1}
  }catch(e){if(alive&&seq===sequence&&session.user?.id===user)error.value=message(e)}finally{if(alive&&seq===sequence)busy.value=false}
}
async function repeat(id:string){
  if(session.user&&pendingFor(session.user.id)){hasPending.value=true;error.value='请先到预约页面恢复尚未核实的提交结果';return}
  await router.push({path:caps.value?.canCreateReservation?'/seat-reservations':'/seat-reservation-demo',query:{repeatReservationId:id}})
}
async function checkIn(id:string){
  checking.value=id;checkInMessage.value={...checkInMessage.value,[id]:''}
  try{const updated=await checkInReservation(id,checkInCodes.value[id]??'');if(page.value)page.value={...page.value,items:page.value.items.map(item=>item.id===id?updated:item)};checkInCodes.value={...checkInCodes.value,[id]:''};await router.push(`/seat-usage/${encodeURIComponent(id)}`)}
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
    <p v-if="!page.items.length" role="status">当前没有本人的预约记录。</p>
    <article v-for="item in page.items" :key="item.id" class="history-card">
      <div class="panel-heading"><h2>{{date(item.startAt)}} — {{date(item.endAt)}}（上海）</h2><span class="badge neutral">{{states[item.status]}}</span></div>
      <p class="mono">预约编号：{{item.id}}</p><details><summary>查看资源编号</summary><p>教室：{{item.roomId}}</p><p>座位：{{item.seatId}}</p><p class="muted">当前教室、座位名称将在再次预约时读取。</p></details>
      <form v-if="item.status==='CONFIRMED'" class="check-in-form" @submit.prevent="checkIn(item.id)"><label :for="`check-in-${item.id}`">教室当日签到码</label><div><input :id="`check-in-${item.id}`" v-model="checkInCodes[item.id]" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" placeholder="6 位数字" required :disabled="checking===item.id"><button class="button" :disabled="!!checking">{{checking===item.id?'正在签到…':'签到'}}</button></div><small>当前体验阶段不限制签到时间；签到码正确后进入座位使用状态。</small><p v-if="checkInMessage[item.id]" class="form-error" role="alert">{{checkInMessage[item.id]}}</p></form>
      <div v-else-if="item.status==='CHECKED_IN'" class="usage-entry"><p class="success-note" role="status">座位正在使用中。</p><RouterLink class="button" :to="`/seat-usage/${item.id}`">进入使用状态</RouterLink></div>
      <button v-if="(caps?.canCreateReservation||caps?.canCreateDemoReservation)&&can('seats.availability.read')" class="button secondary" :disabled="hasPending" @click="repeat(item.id)">再次预约</button>
      <p v-else class="muted">本环境暂未开放预约创建，或当前账号没有所需权限。</p>
    </article>
    <nav v-if="page.items.length||index" class="history-pages" aria-label="本人预约翻页"><button class="button secondary" :disabled="!index||busy" @click="load(index-1)">上一页</button><span>第 {{index+1}} 页</span><button class="button secondary" :disabled="!page.nextCursor||busy" @click="load(index+1)">下一页</button></nav>
  </section>
</template>
<style scoped>.history-panel{padding:24px}.history-card{padding:20px 0;border-bottom:1px solid #dfe6dc}.history-card:first-child{padding-top:0}.history-card h2{font-size:16px}.history-card p{overflow-wrap:anywhere;line-height:1.8}.history-card details{margin:14px 0}.history-pages{display:flex;align-items:center;justify-content:flex-end;gap:14px;padding-top:20px;flex-wrap:wrap}.query-notice{padding:18px;background:#edf4f0;border-radius:12px}.check-in-form{display:grid;gap:8px;max-width:430px;margin:16px 0}.check-in-form label{font-size:12px;color:#6c805d}.check-in-form>div{display:flex;gap:9px}.check-in-form input{width:150px;letter-spacing:3px}.check-in-form small{color:#8a9584;line-height:1.7}.check-in-form .form-error,.check-in-form .success-note{margin:2px 0 0}@media(max-width:650px){.history-panel{padding:18px}.history-card .panel-heading{align-items:flex-start;flex-direction:column;gap:12px}}</style>
