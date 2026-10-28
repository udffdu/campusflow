<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,watch} from 'vue'
import {message} from '../api'
import {campusInstant,m1Get,type QueryRoom,type QueryPage,type SeatCandidates} from '../m1Api'
import Icon from '../components/Icon.vue'
import Pagination from '../components/Pagination.vue'
const rooms=ref<QueryPage<QueryRoom>>({items:[],page:0,size:20,total:0});const roomId=ref('');const roomPage=ref(0)
const roomLoading=ref(false);const roomError=ref('');const error=ref('');const busy=ref(false);const result=ref<SeatCandidates>()
function localHour(date:Date){return new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).format(date).replace(' ','T')}
const next=Math.ceil((Date.now()+1)/3600000)*3600000
const start=ref(localHour(new Date(next)));const end=ref(localHour(new Date(next+3600000)));const power=ref(false)
const room=computed(()=>rooms.value.items.find(r=>r.id===roomId.value))
let roomSequence=0,querySequence=0,alive=true
async function loadRooms(page=0){
  const seq=++roomSequence;roomLoading.value=true;roomError.value='';rooms.value={items:[],page,size:20,total:0};roomId.value=''
  try{const data=await m1Get<QueryPage<QueryRoom>>(`/rooms?page=${page}&size=20`);if(alive&&seq===roomSequence){rooms.value=data;roomPage.value=page;roomId.value=data.items[0]?.id??''}}
  catch(e){if(alive&&seq===roomSequence)roomError.value=message(e)}finally{if(alive&&seq===roomSequence)roomLoading.value=false}
}
watch([roomId,start,end,power],()=>{querySequence++;result.value=undefined;error.value='';busy.value=false})
async function search(page=0){
  const seq=++querySequence;result.value=undefined;error.value='';busy.value=true
  try{
    const params=new URLSearchParams({roomId:roomId.value,startAt:campusInstant(start.value),endAt:campusInstant(end.value),requiresPower:String(power.value),page:String(page),size:'20'})
    const data=await m1Get<SeatCandidates>(`/seat-candidates?${params}`)
    if(alive&&seq===querySequence)result.value=data
  }catch(e){if(alive&&seq===querySequence)error.value=message(e)}finally{if(alive&&seq===querySequence)busy.value=false}
}
const reasons={NONE:'当前条件下没有候选座位，可尝试其他教室或时段。',ROOM_INACTIVE:'教室或上级空间已停用，请刷新教室列表。',OUTSIDE_OPENING_HOURS:'该时段未完整覆盖在教室开放时间内。',USER_TIME_CONFLICT:'你在该时段已有有效预约，请更换时段。'}
onMounted(()=>loadRooms());onUnmounted(()=>{alive=false;querySequence++;roomSequence++})
</script>
<template>
  <div class="page-heading"><div><div class="eyebrow">M1 / SEAT QUERY</div><h1>座位查询</h1><p>选择教室与学习时段，查找符合条件的候选座位。</p></div><span class="badge neutral">只读查询</span></div>
  <div class="query-notice" role="note"><Icon name="shield"/><div><strong>当前仅支持查询，预约暂未开放</strong><p>结果已检查院系范围、开放时间、启停、插座及个人预约占用；尚未接入整教室申请和维修占用，候选结果不保证可预约。</p></div></div>
  <section class="panel query-panel">
    <div class="panel-heading"><h2>查询条件</h2><button class="text-link" :disabled="roomLoading||busy" @click="loadRooms(roomPage)">刷新教室</button></div>
    <p v-if="roomError" role="alert" class="form-error">{{roomError}} <button class="text-link" @click="loadRooms(roomPage)">重试</button></p>
    <p v-else-if="roomLoading" role="status">正在读取有权查询的教室…</p>
    <p v-else-if="!rooms.total" role="status">当前没有已启用且向你开放的教室。</p>
    <form v-else class="query-form" :aria-busy="busy" @submit.prevent="search()">
      <fieldset :disabled="busy||roomLoading">
        <label>教室<select v-model="roomId" required><option v-for="r in rooms.items" :key="r.id" :value="r.id">{{r.code}} · {{r.name}}（{{r.bookingScope==='CAMPUS'?'全校开放':'本院系开放'}}）</option></select></label>
        <div class="query-times"><label>开始时间（上海）<input v-model="start" type="datetime-local" step="3600" required></label><label>结束时间（上海）<input v-model="end" type="datetime-local" step="3600" required></label></div>
        <p class="muted query-help">请选择整点，起止不得跨越未开放时段；提前窗口和单次时长由服务配置校验。</p>
        <details v-if="room"><summary>查看教室每周开放时间</summary><p v-for="(h,i) in room.openingHours" :key="i">周{{'一二三四五六日'[h.day-1]}} {{h.start.slice(0,5)}} — {{h.end.slice(0,5)}}</p><p v-if="!room.openingHours.length">未设置开放时段</p></details>
        <label class="query-checkbox"><input v-model="power" type="checkbox">只看有插座的座位</label>
        <button class="button" type="submit" :disabled="!roomId"><Icon name="search" :size="18"/>{{busy?'正在查询…':'查询候选座位'}}</button>
      </fieldset>
    </form>
    <Pagination v-if="!roomLoading&&!roomError&&rooms.total" :page="roomPage" :size="20" :total="rooms.total" @change="loadRooms($event)"/>
    <p v-if="error" role="alert" class="form-error">{{error}}</p>
  </section>
  <section v-if="result" class="panel query-panel" aria-live="polite">
    <div class="panel-heading"><h2>候选结果 <span class="count-pill">{{result.total}} 个</span></h2><span class="muted">查询不占座</span></div>
    <p v-if="!result.items.length" role="status">{{reasons[result.reason]}}</p>
    <div v-else class="candidate-grid"><article v-for="seat in result.items" :key="seat.id" class="candidate-card"><Icon name="chair" :size="24"/><strong>{{seat.code}}</strong><span class="badge" :class="{neutral:!seat.hasPowerOutlet}">{{seat.hasPowerOutlet?'有插座':'无插座标记'}}</span><small>候选座位 · 暂不可提交预约</small></article></div>
    <Pagination :page="result.page" :size="result.size" :total="result.total" @change="search($event)"/>
  </section>
</template>
<style scoped>
.query-notice{display:flex;gap:14px;padding:18px 22px;background:#edf4f0;border:1px solid #d5e5db;border-radius:12px;margin-bottom:24px;color:#315647}.query-notice p{margin:6px 0 0;line-height:1.7}.query-panel{padding:24px;margin-bottom:24px}.query-form{max-width:800px}.query-form fieldset{border:0;padding:0;margin:0;min-width:0;display:grid;gap:18px}.query-form label:not(.query-checkbox){display:grid;gap:8px}.query-form input:not([type=checkbox]),.query-form select{width:100%}.query-times{display:grid;grid-template-columns:1fr 1fr;gap:18px}.query-help{margin:0;font-size:13px}.query-checkbox{display:flex;align-items:center;gap:10px}.query-checkbox input{width:18px;height:18px}.query-form .button{justify-self:start}.query-form details p{font-size:13px;margin:7px 0}.candidate-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(185px,1fr));gap:16px}.candidate-card{display:flex;align-items:flex-start;flex-direction:column;gap:12px;padding:20px;border:1px solid #e1e8e2;border-radius:12px}.candidate-card small{color:#6d7972;font-size:12px}@media(max-width:650px){.query-times{grid-template-columns:1fr}.query-panel{padding:18px}}
</style>
