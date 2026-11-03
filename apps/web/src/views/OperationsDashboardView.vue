<script setup lang="ts">
import {computed,onMounted,reactive,ref,watch} from 'vue'
import {message} from '../api'
import PageState from '../components/PageState.vue'
import {operationStatuses,queryOperations,shanghaiInstant,type OperationsResponse,type OperationStatus} from '../operations'

const labels:Record<OperationStatus,string>={CONFIRMED:'待签到',CHECKED_IN:'使用中',COMPLETED:'已完成',NO_SHOW:'违约',DISRUPTED:'已中断'}
const statusClass:Record<OperationStatus,string>={CONFIRMED:'pending',CHECKED_IN:'active',COMPLETED:'success',NO_SHOW:'danger',DISRUPTED:'neutral'}
const shifted=new Date(Date.now()+8*3600_000)
const earlier=new Date(shifted.getTime()-6*86400_000).toISOString().slice(0,10)
const later=new Date(shifted.getTime()+86400_000).toISOString().slice(0,10)
const filters=reactive({from:`${earlier}T00:00`,to:`${later}T00:00`,roomId:'',seatId:'',status:''})
const data=ref<OperationsResponse>();const loading=ref(false);const error=ref('');const cursors=ref<(string|null)[]>([null]);const page=ref(0)
const seats=computed(()=>data.value?.resources.seats.filter(seat=>!filters.roomId||seat.roomId===filters.roomId)??[])
watch(()=>filters.roomId,()=>{if(filters.seatId&&!seats.value.some(seat=>seat.id===filters.seatId))filters.seatId=''})
function params(cursor:string|null){const value=new URLSearchParams({from:shanghaiInstant(filters.from),to:shanghaiInstant(filters.to),size:'20'});if(filters.roomId)value.set('roomId',filters.roomId);if(filters.seatId)value.set('seatId',filters.seatId);if(filters.status)value.set('status',filters.status);if(cursor)value.set('cursor',cursor);return value}
async function load(reset=false){if(loading.value)return;if(reset){cursors.value=[null];page.value=0}loading.value=true;error.value='';try{data.value=await queryOperations(params(cursors.value[page.value]??null))}catch(e){error.value=message(e)}finally{loading.value=false}}
async function next(){if(!data.value?.nextCursor)return;cursors.value.splice(page.value+1,Infinity,data.value.nextCursor);page.value++;await load()}
async function previous(){if(page.value===0)return;page.value--;await load()}
const percent=(value:number|null)=>value===null?'—':`${(value*100).toFixed(1)}%`
const duration=(seconds:number)=>{const hours=Math.floor(seconds/3600),minutes=Math.floor(seconds%3600/60);return `${hours} 小时 ${minutes} 分`}
const dateTime=(value:string)=>new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(value))
const roomName=(id:string,code:string|null)=>data.value?.resources.rooms.find(room=>room.id===id)?.name??code??'未知教室'
const seatLabel=(id:string,code:string|null)=>{const seat=data.value?.resources.seats.find(value=>value.id===id);const room=seat?data.value?.resources.rooms.find(value=>value.id===seat.roomId):undefined;return `${room?.code??'未知教室'} / ${code??seat?.code??'未知座位'}`}
onMounted(()=>void load(true))
</script>

<template><div class="operations-page">
  <div class="page-heading"><div><div class="eyebrow">M1 / OPERATIONS</div><h1>预约运营</h1><p>查看本院系自习室与座位的预约、签到和实际使用情况，辅助调整开放与管理策略。</p></div><span class="badge neutral">院系数据范围</span></div>
  <section class="panel filter-panel"><form class="operations-filters" @submit.prevent="load(true)">
    <label>开始时间<input v-model="filters.from" type="datetime-local" required></label><label>结束时间<input v-model="filters.to" type="datetime-local" required></label>
    <label>教室<select v-model="filters.roomId"><option value="">全部教室</option><option v-for="room in data?.resources.rooms??[]" :key="room.id" :value="room.id">{{room.code}} · {{room.name}}</option></select></label>
    <label>座位<select v-model="filters.seatId"><option value="">全部座位</option><option v-for="seat in seats" :key="seat.id" :value="seat.id">{{seat.code}}</option></select></label>
    <label>状态<select v-model="filters.status"><option value="">全部状态</option><option v-for="status in operationStatuses" :key="status" :value="status">{{labels[status]}}</option></select></label>
    <button class="button" :disabled="loading">{{loading?'查询中…':'应用筛选'}}</button>
  </form><p class="scope-hint">查询区间最长 31 天；所有统计均随当前筛选条件变化。日期时间按上海时区解释。</p></section>
  <PageState :loading="loading&&!data" :error="error" @retry="load()"><template v-if="data">
    <section class="operations-metrics" aria-label="运营指标">
      <article><span>预约记录</span><strong>{{data.summary.total}}</strong><small>当前筛选范围</small></article>
      <article><span>签到率</span><strong>{{percent(data.summary.checkInRate)}}</strong><small>已签到与已完成 ÷ 可判定记录</small></article>
      <article class="violation"><span>违约记录</span><strong>{{data.summary.violations}}</strong><small>预约后未完成签到</small></article>
      <article class="utilization"><span>预约时段实际利用率</span><strong>{{percent(data.summary.utilizationRate)}}</strong><small>实际使用 {{duration(data.summary.actualUsageSeconds)}} / 可用预约时长 {{duration(data.summary.reservedDurationSeconds)}}</small></article>
    </section>
    <div class="status-overview" aria-label="预约状态分布"><span v-for="status in operationStatuses" :key="status" :class="statusClass[status]">{{labels[status]}} <strong>{{data.summary.byStatus[status]}}</strong></span></div>
    <p v-if="error" class="form-error" role="alert">{{error}}</p>
    <section class="operations-breakdown">
      <article class="panel"><div class="panel-heading"><div><h2>教室预约排行</h2><p>识别热门与低频教室，辅助调整开放安排。</p></div></div><ol v-if="data.summary.byRoom.length" class="rank-list"><li v-for="(item,index) in data.summary.byRoom.slice(0,8)" :key="item.id"><b>{{index+1}}</b><span>{{item.code??'未知教室'}} · {{roomName(item.id,item.code)}}</span><strong>{{item.count}} 次</strong></li></ol><p v-else class="compact-empty">暂无教室数据</p></article>
      <article class="panel"><div class="panel-heading"><div><h2>座位预约排行</h2><p>观察座位使用偏好，辅助优化设施布局。</p></div></div><ol v-if="data.summary.bySeat.length" class="rank-list"><li v-for="(item,index) in data.summary.bySeat.slice(0,8)" :key="item.id"><b>{{index+1}}</b><span>{{seatLabel(item.id,item.code)}}</span><strong>{{item.count}} 次</strong></li></ol><p v-else class="compact-empty">暂无座位数据</p></article>
    </section>
    <section class="panel records-panel"><div class="panel-heading"><div><h2>预约明细 <span class="count-pill">{{data.total}}</span></h2><p>仅展示资源与生命周期信息，不包含学生姓名、账号或联系方式。</p></div><button class="text-link" :disabled="loading" @click="load()">刷新</button></div>
      <div v-if="!data.items.length" class="state"><strong>暂无匹配记录</strong><p>请调整时间或筛选条件。</p></div>
      <div v-else class="table-scroll"><table><thead><tr><th>教室 / 座位</th><th>预约时段（上海）</th><th>状态</th><th>实际使用</th><th>预约编号</th></tr></thead><tbody><tr v-for="item in data.items" :key="item.id"><td><strong>{{item.roomCode??'未知教室'}} · {{roomName(item.roomId,item.roomCode)}}</strong><small class="subtext">座位 {{item.seatCode??'未知'}}</small></td><td>{{dateTime(item.startAt)}} — {{dateTime(item.endAt)}}</td><td><span class="operation-status" :class="statusClass[item.status]">{{labels[item.status]}}</span></td><td>{{item.checkedInAt?duration(item.usageDurationSeconds):'—'}}</td><td class="mono short-id" :title="item.id">{{item.id}}</td></tr></tbody></table></div>
      <div class="pagination"><span>第 {{page+1}} 页 · 每页最多 20 条</span><div><button class="button secondary" :disabled="loading||page===0" @click="previous">上一页</button><button class="button secondary" :disabled="loading||!data.nextCursor" @click="next">下一页</button></div></div>
    </section>
  </template></PageState>
</div></template>

<style scoped>
.filter-panel{padding:22px;margin-bottom:20px}.operations-filters{display:grid;grid-template-columns:repeat(5,minmax(130px,1fr)) auto;gap:14px;align-items:end}.operations-filters label{display:grid;gap:7px;font-size:10px;color:#829078}.operations-filters input,.operations-filters select{min-width:0}.scope-hint{margin:14px 0 0;color:#94a08b;font-size:10px}.operations-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:15px;margin-bottom:12px}.operations-metrics article{padding:21px;border:1px solid var(--border);border-radius:10px;background:#fff}.operations-metrics span{display:block;color:#77866f;font-size:11px}.operations-metrics strong{display:block;margin:13px 0 7px;color:#244b37;font-size:28px;font-weight:550}.operations-metrics small{color:#9aa590;font-size:9px;line-height:1.7}.operations-metrics .violation{background:#fff8f4}.operations-metrics .violation strong{color:#9a4937}.operations-metrics .utilization{background:#f1f6e9}.status-overview{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:20px}.status-overview span{padding:7px 11px;border-radius:99px;background:#edf1ec;color:#677468;font-size:10px}.status-overview strong{margin-left:5px}.status-overview .success,.status-overview .active{background:#e0f1e3;color:#2b6d45}.status-overview .danger{background:#f9e2d9;color:#993f2d}.status-overview .pending{background:#e7eff4;color:#496c80}.status-overview .neutral{background:#eeeae2;color:#766d5d}.operations-breakdown{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-bottom:20px}.rank-list{list-style:none;margin:0;padding:8px 24px 18px}.rank-list li{display:grid;grid-template-columns:24px 1fr auto;gap:10px;align-items:center;padding:11px 0;border-bottom:1px solid #eff2eb;font-size:11px}.rank-list li:last-child{border-bottom:0}.rank-list b{display:grid;place-items:center;width:21px;height:21px;border-radius:50%;background:#edf2e7;color:#668057;font-size:9px}.rank-list strong{color:#53664d}.records-panel{margin-top:0}.operation-status{display:inline-flex;padding:4px 8px;border-radius:99px;background:#edf1ec;color:#677468}.operation-status.success,.operation-status.active{background:#e0f1e3;color:#2b6d45}.operation-status.danger{background:#f9e2d9;color:#993f2d}.operation-status.pending{background:#e7eff4;color:#496c80}.operation-status.neutral{background:#eeeae2;color:#766d5d}@media(max-width:1150px){.operations-filters{grid-template-columns:repeat(3,1fr)}.operations-metrics{grid-template-columns:1fr 1fr}}@media(max-width:760px){.operations-filters,.operations-metrics,.operations-breakdown{grid-template-columns:1fr}.operations-filters .button{width:100%}.page-heading{align-items:flex-start;flex-direction:column}}
</style>
