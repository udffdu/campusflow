<script setup lang="ts">
import {ref,computed,watch,onUnmounted} from 'vue'
import {can,session} from '../session'
import {ApiError} from '../api'
import {maintenance,maintenanceEnabled,maintenanceMessage,faultNames,type ReportInput,type WorkOrder} from '../maintenance'
const roomId=ref(''),faultType=ref('LIGHTING'),description=ref(''),error=ref(''),busy=ref(false),created=ref<WorkOrder|null>(null)
const attempt=ref<{key:string;body:ReportInput}|null>(null)
const count=computed(()=>Array.from(description.value.trim()).length)
const available=computed(()=>can('maintenance.report')&&maintenanceEnabled())
let revision=0
async function submit(){
 if(busy.value||created.value||!available.value)return
 error.value=''
 if(!attempt.value){
  if(!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(roomId.value.trim())||count.value<5||count.value>1000){error.value='请填写有效房间 UUID 和 5–1000 字描述。';return}
  attempt.value={key:crypto.randomUUID(),body:{roomId:roomId.value.trim(),faultType:faultType.value,description:description.value}}
 }
 busy.value=true
 const ticket=revision
 try {
  const result=await maintenance<WorkOrder>('/work-orders',attempt.value.body,attempt.value.key)
  if(ticket===revision)created.value=result
 }
 catch(e){if(ticket===revision){error.value=maintenanceMessage(e);if(e instanceof ApiError&&e.status===400)attempt.value=null}}
 finally{if(ticket===revision)busy.value=false}
}
function next(){revision++;created.value=null;attempt.value=null;roomId.value='';description.value='';faultType.value='LIGHTING';error.value='';busy.value=false}
watch([()=>session.user?.id,()=>can('maintenance.report')],()=>next())
onUnmounted(()=>revision++)
</script>
<template><div class="page-heading"><div><h1>故障报修</h1><p>上报房间故障，提交后可在本人报修中查看。</p></div><RouterLink v-if="can('maintenance.read.own')" to="/maintenance" class="button secondary">本人报修</RouterLink></div>
<section class="panel"><p v-if="!can('maintenance.report')" role="alert" class="form-error">当前账号没有报修权限。</p><p v-else-if="!maintenanceEnabled()" role="status">报修服务接入尚未开放，请等待入口与权限配置完成。</p>
<div v-else-if="created" class="form-body" role="status"><h2>报修已提交</h2><p>工单号：{{created.id}}</p><p>状态：待派单</p><RouterLink v-if="can('maintenance.read.own')" :to="'/maintenance/'+created.id">查看详情</RouterLink><button class="button" @click="next">新建报修</button></div>
<form v-else class="form-body" @submit.prevent="submit"><fieldset :disabled="busy||!!attempt||!available"><label>房间 UUID<input v-model="roomId" required autocomplete="off"><small>使用空间目录中的房间 ID；服务端将核对房间有效性与院系。</small></label><label>故障类型<select v-model="faultType"><option v-for="(name,code) in faultNames" :key="code" :value="code">{{name}}</option></select></label><label>故障描述<textarea v-model="description" required rows="6"/><small>{{count}} / 1000 字；请勿填写手机号等敏感信息。</small></label></fieldset><p v-if="attempt&&!busy" role="status">上次提交结果尚未确认，重试将使用相同内容。请先查询本人记录，避免重复新建。</p><p v-if="error" class="form-error" role="alert">{{error}}</p><button class="button" :disabled="busy||!available">{{busy?'正在提交…':attempt?'重试原提交':'提交报修'}}</button></form></section></template>
