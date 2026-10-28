<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {message} from '../api'
import {campusInstant,m1Get,type QueryRoom,type QueryPage,type SeatCandidates} from '../m1Api'
import Icon from '../components/Icon.vue'
import Pagination from '../components/Pagination.vue'
import ReservationSubmitPanel from '../components/ReservationSubmitPanel.vue'
import {pendingFor,type Capabilities,type ReservationIntent,type RepeatContext} from '../reservations'
import {session} from '../session'
const props=withDefaults(defineProps<{demo?:boolean}>(),{demo:false})
const route=useRoute();const router=useRouter();const repeat=ref<RepeatContext>()
const repeatId=computed(()=>props.demo?route.query.repeatReservationId:undefined)
const caps=ref<Capabilities>();const pending=ref(false);const selection=ref<ReservationIntent&{seatCode:string;roomCode:string}>()
const rooms=ref<QueryPage<QueryRoom>>({items:[],page:0,size:20,total:0});const roomId=ref('');const roomPage=ref(0)
const roomLoading=ref(false);const roomError=ref('');const error=ref('');const busy=ref(false);const result=ref<SeatCandidates>()
function localHour(date:Date){return new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).format(date).replace(' ','T')}
const next=Math.ceil((Date.now()+1)/3600000)*3600000
const initialStart=localHour(new Date(next)),initialEnd=localHour(new Date(next+3600000))
const startDate=ref(initialStart.slice(0,10)),startHour=ref(initialStart.slice(11,13))
const endDate=ref(initialEnd.slice(0,10)),endHour=ref(initialEnd.slice(11,13))
const hours=Array.from({length:24},(_,hour)=>String(hour).padStart(2,'0'))
const start=computed(()=>startDate.value&&startHour.value?`${startDate.value}T${startHour.value}:00`:'')
const end=computed(()=>endDate.value&&endHour.value?`${endDate.value}T${endHour.value}:00`:'')
const power=ref(false)
const room=computed(()=>rooms.value.items.find(r=>r.id===roomId.value))
const visibleSeats=computed(()=>result.value?.items.filter(s=>!repeat.value||s.id===repeat.value.seat.id)??[])
let roomSequence=0,querySequence=0,alive=true
async function loadRooms(page=0){
  if(pending.value)return
  const seq=++roomSequence;roomLoading.value=true;roomError.value='';rooms.value={items:[],page,size:20,total:0};roomId.value=''
  try{const data=await m1Get<QueryPage<QueryRoom>>(`/rooms?page=${page}&size=20`);if(alive&&seq===roomSequence){rooms.value=data;roomPage.value=page;roomId.value=data.items[0]?.id??''}}
  catch(e){if(alive&&seq===roomSequence)roomError.value=message(e)}finally{if(alive&&seq===roomSequence)roomLoading.value=false}
}
watch([roomId,startDate,startHour,endDate,endHour,power],()=>{querySequence++;result.value=undefined;error.value='';busy.value=false;selection.value=undefined})
async function search(page=0){
  if(pending.value)return
  const seq=++querySequence;result.value=undefined;error.value='';busy.value=true
  try{
    const params=new URLSearchParams({roomId:roomId.value,startAt:campusInstant(start.value),endAt:campusInstant(end.value),requiresPower:String(power.value),page:String(page),size:'20'})
    const data=await m1Get<SeatCandidates>(`/seat-candidates?${params}`)
    if(alive&&seq===querySequence)result.value=data
  }catch(e){if(alive&&seq===querySequence)error.value=message(e)}finally{if(alive&&seq===querySequence)busy.value=false}
}
async function selectSeat(seat:{id:string;code:string}){
  if(pending.value||busy.value)return
  const seq=++querySequence;const chosenRoom=roomId.value,chosenStart=start.value,chosenEnd=end.value,requiresPower=power.value
  if(repeat.value&&seat.id!==repeat.value.seat.id)return
  busy.value=true;error.value=''
  try{
    const params=new URLSearchParams({roomId:chosenRoom,startAt:campusInstant(chosenStart),endAt:campusInstant(chosenEnd),requiresPower:String(requiresPower),page:String(result.value?.page??0),size:'20'})
    const current=await m1Get<SeatCandidates>(`/seat-candidates?${params}`)
    if(!alive||seq!==querySequence)return
    if(!current.items.some(s=>s.id===seat.id))throw new Error('候选结果已变化，请重新查询后选择')
    selection.value={roomId:chosenRoom,seatId:seat.id,startAt:campusInstant(chosenStart),endAt:campusInstant(chosenEnd),seatCode:seat.code,roomCode:room.value?.code??chosenRoom}
  }catch(e){if(alive&&seq===querySequence)error.value=message(e)}finally{if(alive&&seq===querySequence)busy.value=false}
}
async function initialize(){
  querySequence++;roomSequence++;selection.value=undefined;result.value=undefined;repeat.value=undefined
  if(!repeatId.value){await loadRooms();return}
  startDate.value='';startHour.value='';endDate.value='';endHour.value='';power.value=false;roomId.value='';rooms.value={items:[],page:0,size:20,total:0}
  if(session.user&&pendingFor(session.user.id)){roomError.value='请先恢复尚未核实的原提交结果，再从我的预约重新选择';return}
  const seq=++roomSequence;roomLoading.value=true;roomError.value=''
  try{
    if(typeof repeatId.value!=='string')throw new Error('再次预约入口无效，请回到我的预约重新选择')
    const value=await m1Get<RepeatContext>(`/reservations/${encodeURIComponent(repeatId.value)}/repeat-context`)
    if(alive&&seq===roomSequence){repeat.value=value;rooms.value={items:[value.room],page:0,size:20,total:1};roomId.value=value.room.id}
  }catch(e){if(alive&&seq===roomSequence)roomError.value=message(e)}finally{if(alive&&seq===roomSequence)roomLoading.value=false}
}
watch(()=>repeatId.value,()=>{void initialize()},{immediate:true})
const reasons={NONE:'当前条件下没有候选座位，可尝试其他教室或时段。',ROOM_INACTIVE:'教室或上级空间已停用，请刷新教室列表。',OUTSIDE_OPENING_HOURS:'该时段未完整覆盖在教室开放时间内。',USER_TIME_CONFLICT:'你在该时段已有有效预约，请更换时段。'}
onMounted(()=>{if(props.demo)void m1Get<Capabilities>('/reservation-capabilities').then(value=>{if(alive)caps.value=value}).catch(e=>{if(alive)error.value=message(e)})});onUnmounted(()=>{alive=false;querySequence++;roomSequence++})
</script>
<template>
  <div class="page-heading"><div><div class="eyebrow">M1 / SEAT QUERY</div><h1>{{demo?'个人预约演示':'座位查询'}}</h1><p>选择教室与学习时段，查找符合条件的候选座位。</p></div><span class="badge neutral">{{demo?'独立演示':'只读查询'}}</span></div>
  <div class="query-notice" role="note"><Icon name="shield"/><div><strong>{{demo?'仅本地演示，正式预约暂未开放':'当前仅支持查询，预约暂未开放'}}</strong><p>结果已检查院系范围、开放时间、启停、插座及个人预约占用；尚未接入整教室申请和维修占用，候选结果不保证可预约。</p></div></div>
  <div v-if="repeatId" class="query-notice" role="note"><div><strong>再次预约{{repeat?' · '+repeat.room.code+' · '+repeat.seat.code:''}}</strong><p>请重新填写开始和结束时间。旧预约保持不变；查询和提交都会重新校验当前条件。</p><button class="text-link" :disabled="pending||busy" @click="router.replace('/seat-reservation-demo')">重新选择其他座位</button></div></div>
  <section class="panel query-panel">
    <div class="panel-heading"><h2>查询条件</h2><button class="text-link" :disabled="roomLoading||busy||pending" @click="initialize">刷新教室</button></div>
    <p v-if="roomError" role="alert" class="form-error">{{roomError}} <button class="text-link" @click="initialize">重试</button></p>
    <p v-else-if="roomLoading" role="status">正在读取有权查询的教室…</p>
    <p v-else-if="!rooms.total" role="status">当前没有已启用且向你开放的教室。</p>
    <form v-else class="query-form" :aria-busy="busy" @submit.prevent="search()">
      <fieldset :disabled="busy||roomLoading||pending">
        <label>教室<select v-model="roomId" required><option v-for="r in rooms.items" :key="r.id" :value="r.id">{{r.code}} · {{r.name}}（{{r.bookingScope==='CAMPUS'?'全校开放':'本院系开放'}}）</option></select></label>
        <div class="query-times">
          <div class="query-time"><label>开始日期（上海）<input v-model="startDate" name="start-date" type="date" required></label><label>开始时间<select v-model="startHour" name="start-hour" required><option value="" disabled>请选择</option><option v-for="hour in hours" :key="hour" :value="hour">{{hour}}时</option></select></label></div>
          <div class="query-time"><label>结束日期（上海）<input v-model="endDate" name="end-date" type="date" required></label><label>结束时间<select v-model="endHour" name="end-hour" required><option value="" disabled>请选择</option><option v-for="hour in hours" :key="hour" :value="hour">{{hour}}时</option></select></label></div>
        </div>
        <p class="muted query-help">按整点预约，分钟固定为00。结束时间须晚于开始时间，且整个时段都在开放时间内；预约窗口和单次时长由服务校验。</p>
        <details v-if="room"><summary>查看教室每周开放时间</summary><p v-for="(h,i) in room.openingHours" :key="i">周{{'一二三四五六日'[h.day-1]}} {{h.start.slice(0,5)}} — {{h.end.slice(0,5)}}</p><p v-if="!room.openingHours.length">未设置开放时段</p></details>
        <label class="query-checkbox"><input v-model="power" type="checkbox">只看有插座的座位</label>
        <button class="button" type="submit" :disabled="!roomId"><Icon name="search" :size="18"/>{{busy?'正在查询…':'查询候选座位'}}</button>
      </fieldset>
    </form>
    <Pagination v-if="!repeatId&&!roomLoading&&!roomError&&rooms.total&&!pending" :page="roomPage" :size="20" :total="rooms.total" @change="loadRooms($event)"/>
    <p v-if="error" role="alert" class="form-error">{{error}}</p>
  </section>
  <p v-if="demo&&caps&&!caps.canCreateDemoReservation" role="status" class="form-error">本环境未开放演示提交，或当前账号没有创建权限。</p>
  <ReservationSubmitPanel v-if="demo" :selection="selection" :can-create="caps?.canCreateDemoReservation??false" @pending="pending=$event" @created="result=undefined;selection=undefined"/>
  <section v-if="result" class="panel query-panel" aria-live="polite">
    <div class="panel-heading"><h2>{{repeat?'本页原座位结果':'候选结果'}} <span class="count-pill">{{repeat?visibleSeats.length:result.total}} 个</span></h2><span class="muted">查询不占座</span></div>
    <p v-if="!result.items.length" role="status">{{reasons[result.reason]}}</p>
    <p v-else-if="repeat&&!visibleSeats.length" role="status">此页没有原座位，请翻页核对；若所有页均无此座位，请换时段或重新选择其他座位。</p>
    <div class="candidate-grid"><article v-for="seat in visibleSeats" :key="seat.id" class="candidate-card"><Icon name="chair" :size="24"/><strong>{{seat.code}}</strong><span class="badge" :class="{neutral:!seat.hasPowerOutlet}">{{seat.hasPowerOutlet?'有插座':'无插座标记'}}</span><button v-if="demo&&caps?.canCreateDemoReservation" class="button secondary" :disabled="pending||busy" @click="selectSeat(seat)">选择此座位</button><small v-else>候选座位 · 暂不可提交预约</small></article></div>
    <Pagination v-if="!pending" :page="result.page" :size="result.size" :total="result.total" @change="search($event)"/>
  </section>
</template>
<style scoped>
.query-notice{display:flex;gap:14px;padding:18px 22px;background:#edf4f0;border:1px solid #d5e5db;border-radius:12px;margin-bottom:24px;color:#315647}.query-notice p{margin:6px 0 0;line-height:1.7}.query-panel{padding:24px;margin-bottom:24px}.query-form{max-width:800px}.query-form fieldset{border:0;padding:0;margin:0;min-width:0;display:grid;gap:18px}.query-form label:not(.query-checkbox){display:grid;gap:8px}.query-form input:not([type=checkbox]),.query-form select{width:100%}.query-times{display:grid;grid-template-columns:1fr 1fr;gap:18px}.query-time{display:grid;grid-template-columns:minmax(0,1fr) 96px;gap:12px;min-width:0}.query-help{margin:0;font-size:13px}.query-checkbox{display:flex;align-items:center;gap:10px}.query-checkbox input{width:18px;height:18px}.query-form .button{justify-self:start}.query-form details p{font-size:13px;margin:7px 0}.candidate-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(185px,1fr));gap:16px}.candidate-card{display:flex;align-items:flex-start;flex-direction:column;gap:12px;padding:20px;border:1px solid #e1e8e2;border-radius:12px}.candidate-card small{color:#6d7972;font-size:12px}@media(max-width:650px){.query-times{grid-template-columns:1fr}.query-panel{padding:18px}}
</style>
