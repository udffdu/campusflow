<script setup lang="ts">
import {onMounted,onUnmounted,ref,watch} from 'vue'
import {message} from '../api'
import {m1Get} from '../m1Api'
import {session} from '../session'
import {definitiveFailure,validOwnReservation,finishPending,pendingFor,savePending,submitReservation,type OwnReservation,type PendingReservation,type ReservationIntent} from '../reservations'
const props=withDefaults(defineProps<{selection?:ReservationIntent&{seatCode:string;roomCode:string};canCreate:boolean;demo?:boolean}>(),{demo:true})
const emit=defineEmits<{pending:[boolean];created:[OwnReservation]}>()
const pending=ref<PendingReservation>();const record=ref<OwnReservation>();const recordLabel=ref('');const busy=ref(false);const error=ref('');const unknown=ref(false)
const states:Record<string,string>={CONFIRMED:'已预约',CHECKED_IN:'已签到',COMPLETED:'已完成',NO_SHOW:'未签到取消',DISRUPTED:'系统原因终止'}
let alive=true;const userId=session.user?.id??''
watch(pending,p=>emit('pending',!!p),{immediate:true})
watch(()=>props.selection,(value,old)=>{if(value&&value!==old&&!pending.value)record.value=undefined})
function date(value:string){return new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',dateStyle:'short',timeStyle:'short',hourCycle:'h23'}).format(new Date(value))}
function complete(value:OwnReservation){if(!alive||session.user?.id!==userId)return;if(!validOwnReservation(value))throw new Error('响应不完整，请保留原请求恢复结果');recordLabel.value=`${pending.value?.roomCode??props.selection?.roomCode??''} · ${pending.value?.seatCode??props.selection?.seatCode??''}`;record.value=value;finishPending(userId);pending.value=undefined;unknown.value=false;error.value='';emit('created',value)}
async function send(){
  if(busy.value||!props.canCreate)return
  busy.value=true;error.value=''
  try{
    if(!pending.value){if(!props.selection)return;const {roomCode,seatCode,...intent}=props.selection;const p={userId,key:crypto.randomUUID(),intent,seatCode,roomCode};savePending(p);pending.value=p}
    const value=await submitReservation(pending.value!);complete(value)
  }catch(e){if(alive){error.value=message(e);if(!pending.value){error.value="无法保存本次请求编号，尚未发送预约。请检查浏览器会话存储后再提交。";unknown.value=false}else if(definitiveFailure(e)){finishPending(userId);pending.value=undefined;unknown.value=false}else unknown.value=true}}
  finally{if(alive)busy.value=false}
}
async function recover(){
  if(!pending.value||busy.value)return;busy.value=true;error.value=''
  try{const value=await m1Get<OwnReservation>(`/reservation-requests/${encodeURIComponent(pending.value.key)}`);complete(value)}
  catch(e){if(alive){error.value=message(e);unknown.value=true}}finally{if(alive)busy.value=false}
}
async function refresh(){if(!record.value||busy.value)return;busy.value=true;error.value='';try{const value=await m1Get<OwnReservation>(`/reservations/${record.value.id}`);if(alive)record.value=value}catch(e){if(alive)error.value=`记录已保存。${message(e)}`}finally{if(alive)busy.value=false}}
onMounted(()=>{pending.value=pendingFor(userId);if(pending.value){unknown.value=true;void recover()}})
onUnmounted(()=>{alive=false})
</script>
<template>
  <section v-if="pending||selection||record" class="panel reservation-panel" aria-live="polite">
    <template v-if="pending||selection"><h2>{{pending?'正在核实这次提交':demo?'确认演示预约':'确认预约'}}</h2>
      <p>{{pending?.roomCode??selection?.roomCode}} · {{pending?.seatCode??selection?.seatCode}}</p>
      <p>{{date((pending?.intent??selection!).startAt)}} — {{date((pending?.intent??selection!).endAt)}}（上海时间）</p>
      <p class="muted">{{demo?'仅本地演示，尚未校验整教室申请和维修限制。':'当前仅校验个人预约；整教室申请和维修限制待后续接入。'}}</p>
      <p v-if="unknown" role="status">提交结果尚未核实。保留了原请求编号；先恢复结果，重试也不会换一个编号。</p>
      <div class="reservation-actions"><button v-if="pending" class="button secondary" :disabled="busy" @click="recover">查询提交结果</button><button v-if="canCreate" class="button" :disabled="busy" @click="send">{{busy?'正在处理…':pending?'使用原请求重试':demo?'提交演示预约':'提交预约'}}</button></div>
    </template>
    <div v-if="record" class="saved-record"><h2>{{record.stage==='PERSONAL_DEMO'?'演示预约已保存':'预约已保存'}} · {{states[record.status]??record.status}}</h2><p class="mono break">预约编号：{{record.id}}</p><p>{{date(record.startAt)}} — {{date(record.endAt)}}（上海时间）</p><p>{{recordLabel}}</p><button class="text-link" :disabled="busy" @click="refresh">刷新本人记录</button></div>
    <p v-if="error" role="alert" class="form-error">{{error}}</p>
  </section>
</template>
<style scoped>.reservation-panel{padding:24px;margin-bottom:24px;display:grid;gap:16px}.reservation-panel p{line-height:1.8;margin-top:8px;overflow-wrap:anywhere}.reservation-actions{display:flex;gap:12px;flex-wrap:wrap;margin-top:16px}.saved-record{border-top:1px solid #dfe6dc;padding-top:16px}</style>
