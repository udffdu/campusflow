<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { allPages, api, message } from '../api'
import { can, inScope, session } from '../session'
import type { Space } from '../types'
import { applicationStates, historyActions, shanghaiTime, toInstant, type ApplicationDetail, type RoomApplication, type RoomPolicy } from '../rooms'
import { useList } from '../useList'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import PageState from '../components/PageState.vue'
import Pagination from '../components/Pagination.vue'
const route=useRoute()
const {data,loading,error,load}=useList<RoomApplication>()
const view=ref('mine');const status=ref('');const page=ref(0)
const policy=ref<RoomPolicy|null>(null);const rooms=ref<Space[]>([]);const createOpen=ref(false)
const form=ref({requestKey:'',roomId:'',title:'',purpose:'',attendees:1,startAt:'',endAt:''})
const busy=ref(false);const formError=ref('');const notice=ref('');const detail=ref<ApplicationDetail|null>(null)
const detailLoading=ref(false);const detailError=ref('');const comment=ref('');const action=ref('')
const reviewer=computed(()=>can('rooms.review')||can('rooms.approve'))
const selectedRoom=computed(()=>rooms.value.find(r=>r.id===form.value.roomId))
const app=computed(()=>detail.value?.application)
const canDecide=computed(()=>!!app.value&&app.value.status==='PENDING'&&Date.parse(app.value.deadline)>Date.now()&&app.value.applicantId!==session.user?.id&&inScope(app.value.departmentId)&&can(app.value.steps[app.value.currentStep]?.permission??'')&&!detail.value?.history.some(h=>h.action==='STEP_APPROVED'&&h.actorId===session.user?.id))
const canCancel=computed(()=>!!app.value&&['PENDING','PROCESSING','APPROVED'].includes(app.value.status)&&(app.value.status!=='APPROVED'||Date.parse(app.value.startAt)>Date.now())&&(app.value.applicantId===session.user?.id||(can('rooms.manage')&&inScope(app.value.departmentId))))
const canRetry=computed(()=>!!app.value&&can('rooms.manage')&&inScope(app.value.departmentId)&&(['PROCESSING','RELEASING'].includes(app.value.status)||detail.value?.deliveries.some(e=>e.status==='DEAD')))
function reload(){return load(`/room-applications?view=${view.value}&status=${status.value}&page=${page.value}&size=20`)}
watch([view,status],()=>{page.value=0;void reload()});watch(page,reload)
async function prepare(){formError.value='';try{policy.value=await api<RoomPolicy>('/room-applications/policy');rooms.value=(await allPages<Space>('/resources?type=ROOM')).filter(r=>r.effectiveActive&&inScope(r.departmentId))}catch(e){formError.value=message(e)}}
async function create(){form.value={requestKey:crypto.randomUUID(),roomId:'',title:'',purpose:'',attendees:1,startAt:'',endAt:''};createOpen.value=true;await prepare()}
async function submit(){busy.value=true;formError.value='';try{const result=await api<ApplicationDetail>('/room-applications','POST',{...form.value,startAt:toInstant(form.value.startAt),endAt:toInstant(form.value.endAt)});createOpen.value=false;detail.value=result;comment.value='';action.value='';detailError.value='';notice.value='申请已提交，待审批期间不会占用教室。';await reload()}catch(e){formError.value=message(e)}finally{busy.value=false}}
let detailSerial=0
async function openDetail(id:string){const serial=++detailSerial;detailLoading.value=true;detailError.value='';try{const result=await api<ApplicationDetail>(`/room-applications/${id}`);if(serial===detailSerial){detail.value=result;comment.value='';action.value=''}}catch(e){if(serial===detailSerial)detailError.value=message(e)}finally{if(serial===detailSerial)detailLoading.value=false}}
function closeDetail(){detailSerial++;detail.value=null;detailError.value='';action.value='';comment.value=''}
async function execute(){if(!app.value||!action.value)return;busy.value=true;detailError.value='';try{const current=app.value;const decision=['APPROVE','REJECT'].includes(action.value);detail.value=await api<ApplicationDetail>(`/room-applications/${current.id}/${decision?'decisions':action.value}`,'POST',{expectedVersion:current.version,comment:comment.value,...(decision?{step:current.currentStep,decision:action.value}:{})});comment.value='';action.value='';await reload()}catch(e){detailError.value=message(e)}finally{busy.value=false}}
let poll:ReturnType<typeof setInterval>
onMounted(async()=>{void reload();if(typeof route.query.id==='string')void openDetail(route.query.id);poll=setInterval(()=>{if(!busy.value&&!createOpen.value&&!detail.value)void reload()},15000)})
onUnmounted(()=>{clearInterval(poll);detailSerial++})
</script>
<template>
  <div class="page-heading"><div><div class="eyebrow">CAMPUS / ROOM APPLICATIONS</div><h1>教室申请</h1><p>为一场讨论、一次活动，找到合适的空间。</p></div><button v-if="can('rooms.apply')" class="button" @click="create"><Icon name="plus" :size="17"/>申请教室</button></div>
  <div class="room-flow" aria-label="申请流程"><span><b>01</b> 提交申请</span><i>→</i><span><b>02</b> 院系初审</span><i>→</i><span><b>03</b> 教室终审</span><i>→</i><span><b>04</b> 确认占用</span></div>
  <div class="info-strip"><Icon name="shield" :size="18"/><p>审批通过后，须确认教室占用才算预约成功。所有时间均为北京时间；待审超时会自动撤销。</p></div>
  <p v-if="notice" role="status" class="room-notice">{{notice}}</p>
  <p v-if="detailError&&!detail" class="form-error" role="alert">{{detailError}}</p>
  <section class="panel"><div class="toolbar"><div class="tab-filters" aria-label="申请范围"><button :class="{selected:view==='mine'}" @click="view='mine'">我的申请</button><button v-if="reviewer" :class="{selected:view==='review'}" @click="view='review'">审批待办</button><button v-if="can('rooms.manage')" :class="{selected:view==='manage'}" @click="view='manage'">申请管理</button></div><div class="room-filters"><select v-model="status" aria-label="申请状态"><option value="">全部状态</option><option v-for="(label,key) in applicationStates" :key="key" :value="key">{{label}}</option></select><button class="button secondary" :disabled="loading" @click="reload">刷新</button></div></div>
    <PageState :loading="loading" :error="error" :empty="!data.items.length" :empty-text="view==='review'?'暂无需要你审批的申请':'暂无匹配的申请'" @retry="reload"><div class="table-scroll"><table><thead><tr><th>申请 / 教室</th><th>使用时段 · 北京时间</th><th>申请人</th><th>审批进度</th><th>状态</th><th class="align-right">操作</th></tr></thead><tbody><tr v-for="item in data.items" :key="item.id"><td><strong>{{item.title}}</strong><small class="room-cell-sub">{{item.roomName}} · {{item.attendees}} 人</small></td><td class="nowrap">{{shanghaiTime(item.startAt)}}<small class="room-cell-sub">至 {{shanghaiTime(item.endAt)}}</small></td><td>{{item.applicantName}}</td><td>{{item.status==='PENDING'?item.steps[item.currentStep]?.name:item.currentStep>=item.steps.length?'审批节点完成':'已结束'}}<small v-if="item.status==='PENDING'" class="room-cell-sub">截止 {{shanghaiTime(item.deadline)}}</small></td><td><span class="badge" :class="{neutral:['REJECTED','CANCELLED','EXPIRED','CONFLICT'].includes(item.status),warning:['PROCESSING','RELEASING'].includes(item.status)}">{{applicationStates[item.status]}}</span><small v-if="item.needsAttention" class="room-cell-sub room-warning">需要管理员检查</small></td><td class="align-right"><button class="text-link" :disabled="detailLoading" @click="openDetail(item.id)">{{view==='review'?'查看并审批':'查看详情'}}</button></td></tr></tbody></table></div><Pagination :page="page" :size="20" :total="data.total" @change="page=$event"/></PageState>
  </section>
  <Modal v-if="createOpen" title="申请整间教室" @close="!busy&&(createOpen=false)"><form class="form-body" @submit.prevent="submit">
    <p v-if="policy" class="muted small-text">30 分钟为一个时段，单次最长 {{policy.maxHours}} 小时；提前 {{policy.minLeadMinutes}} 分钟至 {{policy.advanceDays}} 天申请。待审最多 {{policy.approvalHours}} 小时，且不晚于使用开始。</p>
    <label>教室<select v-model="form.roomId" required><option value="" disabled>选择本院系启用的教室</option><option v-for="room in rooms" :key="room.id" :value="room.id">{{room.name}} · {{room.capacity}} 人</option></select></label>
    <div v-if="selectedRoom" class="room-opening"><strong>每周开放时段</strong><p v-for="(h,index) in selectedRoom.openingHours" :key="index">周{{['一','二','三','四','五','六','日'][h.day-1]}} {{h.start.slice(0,5)}}—{{h.end.slice(0,5)}}</p><p v-if="!selectedRoom.openingHours.length">尚未开放，请选择其他教室。</p></div>
    <label>活动名称<input v-model="form.title" required maxlength="100" placeholder="如：课程项目讨论"></label><label>用途说明<textarea v-model="form.purpose" required maxlength="2000" rows="3" placeholder="说明活动内容及申请整间教室的原因"/></label>
    <div class="form-grid"><label>开始时间 · 北京时间<input v-model="form.startAt" type="datetime-local" required step="1800"></label><label>结束时间 · 北京时间<input v-model="form.endAt" type="datetime-local" required step="1800"></label></div>
    <label>参与人数<input v-model.number="form.attendees" type="number" required min="1" :max="selectedRoom?.capacity??10000"></label>
    <p v-if="formError" class="form-error" role="alert">{{formError}}</p><div class="form-actions"><button type="button" class="button secondary" :disabled="busy" @click="createOpen=false">取消</button><button class="button" :disabled="busy||!policy||!selectedRoom?.openingHours.length">{{busy?'正在提交…':'提交申请'}}</button></div>
  </form></Modal>
  <Modal v-if="detail&&app" :title="app.title" @close="!busy&&closeDetail()"><div class="form-body">
    <div class="room-detail-heading"><span class="badge" :class="{warning:['PROCESSING','RELEASING'].includes(app.status)}">{{applicationStates[app.status]}}</span><button class="text-link" :disabled="busy||detailLoading" @click="openDetail(app.id)">刷新进度</button></div>
    <p v-if="app.status==='PROCESSING'" class="room-notice">审批节点已完成，正在确认教室占用。此时仍未预约成功。</p><p v-if="app.status==='RELEASING'" class="room-notice">撤销正在处理，尚未确认释放，请稍后刷新。</p><p v-if="app.status==='APPROVED'" class="room-notice">已确认本次申请占用。后续维修或临时停用可能影响实际使用。</p><p v-if="app.needsAttention" class="form-error">确认操作暂未成功，系统会继续重试，请联系教室管理员检查。</p>
    <dl class="detail-list"><dt>教室</dt><dd>{{app.roomName}}</dd><dt>申请人</dt><dd>{{app.applicantName}} · {{app.attendees}} 人</dd><dt>使用时间</dt><dd>{{shanghaiTime(app.startAt)}}<br>至 {{shanghaiTime(app.endAt)}}（北京时间）</dd><dt>用途说明</dt><dd class="room-purpose">{{app.purpose}}</dd><dt>审批截止</dt><dd>{{shanghaiTime(app.deadline)}}</dd><dt>申请编号</dt><dd class="mono break">{{app.id}}</dd></dl>
    <div class="room-timeline"><h3>申请进度</h3><ol><li v-for="entry in detail.history" :key="entry.id"><span class="room-timeline-dot"/><strong>{{historyActions[entry.action]??entry.action}}</strong><small>{{entry.actorName}}{{entry.step!==null?' · '+app.steps[entry.step]?.name:''}} · {{shanghaiTime(entry.occurredAt)}}</small><p>{{entry.comment}}</p></li></ol></div>
    <p v-if="detail.deliveries.some(e=>e.status!=='SENT')" class="muted small-text">部分通知或审计正在补发；申请记录已保存。{{detail.deliveries.some(e=>e.status==='DEAD')?'请管理员检查失败任务并重试。':''}}</p>
    <details v-if="can('rooms.manage')&&inScope(app.departmentId)" class="room-diagnostics"><summary>恢复任务详情</summary><p>占用操作尝试 {{app.attempts}} 次；{{app.lastError||'暂无错误'}}。</p><p v-if="['PROCESSING','RELEASING'].includes(app.status)">下次检查：{{shanghaiTime(app.nextAttemptAt)}}</p><p v-for="event in detail.deliveries.filter(e=>e.status!=='SENT')" :key="event.id">{{event.kind==='AUDIT'?'审计':'通知'}} · {{event.status==='DEAD'?'需人工重试':'待发送'}} · {{event.lastError||'等待处理'}}</p></details>
    <p v-if="detailError" class="form-error" role="alert">{{detailError}}</p>
    <template v-if="action"><form @submit.prevent="execute"><label>{{action==='REJECT'?'驳回原因':action==='APPROVE'?'审批意见':action==='cancel'?'撤销原因':'恢复说明'}}<textarea v-model="comment" required maxlength="1000" rows="3"/></label><div class="form-actions"><button type="button" class="button secondary" :disabled="busy" @click="action='';comment=''">返回</button><button class="button" :disabled="busy">{{busy?'正在处理…':'确认'+(action==='APPROVE'?'通过':action==='REJECT'?'驳回':action==='cancel'?'撤销':'重试')}}</button></div></form></template>
    <div v-else class="form-actions room-actions"><button v-if="canRetry" class="button secondary" @click="action='retry'">重试恢复任务</button><button v-if="canCancel" class="button secondary" @click="action='cancel'">撤销申请</button><button v-if="canDecide" class="button secondary" @click="action='REJECT'">驳回</button><button v-if="canDecide" class="button" @click="action='APPROVE'">通过本节点</button><button v-if="!canCancel&&!canDecide&&!canRetry" class="button secondary" @click="closeDetail">关闭</button></div>
  </div></Modal>
</template>
