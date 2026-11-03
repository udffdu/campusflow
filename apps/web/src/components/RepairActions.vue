<script setup lang="ts">
import {computed,ref,watch,onUnmounted} from 'vue'
import {can,session} from '../session'
import {maintenance,maintenanceMessage} from '../maintenance'
import {ApiError} from '../api'
const props=defineProps<{order:{id:string;status:string;version:number;assigneeId:string|null};mode:'repair'|'accept'}>()
const emit=defineEmits<{done:[]}>()
const remark=ref(''),busy=ref(false),error=ref(''),conflict=ref(false)
const attempt=ref<{action:string;key:string;body:{expectedVersion:number;remark:string}}|null>(null)
let revision=0
const eligible=computed(()=>props.mode==='repair'?can('maintenance.repair')&&session.user?.id===props.order.assigneeId:can('maintenance.accept')&&session.user?.id!==props.order.assigneeId)
const actions=computed(()=>!eligible.value?[]:props.mode==='repair'?(props.order.status==='ASSIGNED'?[{key:'start',label:'开始维修'}]:props.order.status==='IN_PROGRESS'?[{key:'completion',label:'提交完工'}]:[]):props.order.status==='PENDING_ACCEPTANCE'?[{key:'pass',label:'验收通过'},{key:'reject',label:'驳回返修'}]:[])
watch([()=>props.order.id,()=>props.order.version,()=>props.order.status,()=>session.user?.id,eligible],()=>{revision++;remark.value='';busy.value=false;error.value='';conflict.value=false;attempt.value=null})
onUnmounted(()=>revision++)
async function submit(action:string){
 if(busy.value||conflict.value||!eligible.value||!actions.value.some(a=>a.key===action))return
 if(attempt.value&&attempt.value.action!==action)return
 const value=remark.value.trim()
 if(!attempt.value&&((['completion','reject'].includes(action)&&!value)||[...value].length>1000)){error.value='完工说明或驳回原因必填，说明最多 1000 个字符。';return}
 if(!attempt.value)attempt.value={action,key:crypto.randomUUID(),body:{expectedVersion:props.order.version,remark:value}}
 const current=attempt.value,ticket=revision;busy.value=true;error.value=''
 const path=props.mode==='repair'?`/repair/work-orders/${encodeURIComponent(props.order.id)}/${action}`:`/work-orders/${encodeURIComponent(props.order.id)}/acceptance/${action}`
 try{await maintenance(path,current.body,current.key);if(ticket===revision)emit('done')}
 catch(e){if(ticket!==revision)return;error.value=maintenanceMessage(e);if(e instanceof ApiError&&e.status===409){conflict.value=true;error.value='工单状态或版本已变化，请刷新后确认。'}else if(e instanceof ApiError&&e.status===400)attempt.value=null}
 finally{if(ticket===revision)busy.value=false}
}
</script>
<template><section v-if="actions.length" class="form-body"><h3>{{mode==='repair'?'维修处理':'完工验收'}}</h3><label>处理说明<textarea v-model="remark" :disabled="busy||!!attempt" rows="3" placeholder="完工说明、驳回原因必填；最多 1000 个字符"/></label><p v-if="error" class="form-error" role="alert">{{error}}</p><p v-if="attempt&&!conflict">重试将保留原动作、说明、版本与请求编号。</p><div class="form-actions"><button v-for="action in actions" :key="action.key" class="button" :disabled="busy||conflict||!!attempt&&attempt.action!==action.key" @click="submit(action.key)">{{busy?'正在提交…':attempt?.action===action.key?'重试原操作':action.label}}</button><button v-if="conflict" class="button secondary" @click="emit('done')">刷新工单</button></div></section></template>
