<script setup lang="ts">
import {ref,watch,onUnmounted} from 'vue'
import {can,session} from '../session'
import {maintenance,maintenanceMessage} from '../maintenance'
import {ApiError} from '../api'
import CandidatePicker from './CandidatePicker.vue'
const props=defineProps<{orderId:string;version:number}>(),emit=defineEmits<{done:[]}>()
const selected=ref<{id:string}|null>(null),busy=ref(false),error=ref(''),conflict=ref(false),attempt=ref<{key:string;body:{assigneeId:string;expectedVersion:number}}|null>(null)
let revision=0
watch([()=>props.orderId,()=>props.version,()=>session.user?.id,()=>can('maintenance.dispatch')],()=>{revision++;selected.value=null;attempt.value=null;error.value='';conflict.value=false;busy.value=false})
onUnmounted(()=>revision++)
async function submit(){if(busy.value||conflict.value||!can('maintenance.dispatch')||(!selected.value&&!attempt.value))return
 if(!attempt.value)attempt.value={key:crypto.randomUUID(),body:{assigneeId:selected.value!.id,expectedVersion:props.version}}
 busy.value=true;error.value='';const ticket=revision
 try{await maintenance('/management/work-orders/'+encodeURIComponent(props.orderId)+'/dispatch',attempt.value.body,attempt.value.key);if(ticket===revision)emit('done')}
 catch(e){if(ticket!==revision)return;error.value=maintenanceMessage(e);if(e instanceof ApiError&&e.status===409){conflict.value=true;error.value='工单状态、版本或人员资格已变化，请刷新工单并重新选择。'}}finally{if(ticket===revision)busy.value=false}}
</script>
<template><section><fieldset :disabled="busy||!!attempt"><CandidatePicker :order-id="orderId" @select="selected=$event"/></fieldset><p v-if="error" role="alert" class="form-error">{{error}}</p><p v-if="attempt&&!conflict">失败重试保留原人员、版本和请求编号；离开前请核对派单结果。</p><button class="button" :disabled="busy||conflict||!can('maintenance.dispatch')||(!selected&&!attempt)" @click="submit">{{busy?'正在派单…':attempt?'重试原派单':'确认派单'}}</button><button v-if="conflict" class="button secondary" @click="emit('done')">刷新工单</button></section></template>
