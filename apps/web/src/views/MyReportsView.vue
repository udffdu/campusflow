<script setup lang="ts">
import {ref,watch,onUnmounted} from 'vue'
import {useRoute} from 'vue-router'
import {can,session} from '../session'
import {maintenance,maintenanceMessage,stateName,faultNames,type WorkOrder} from '../maintenance'
import type {Page} from '../types'
import WorkOrderHistory from '../components/WorkOrderHistory.vue'
import PageState from '../components/PageState.vue'
import Pagination from '../components/Pagination.vue'
import RepairActions from '../components/RepairActions.vue'
const route=useRoute(),loading=ref(false),error=ref(''),page=ref(0),data=ref<Page<WorkOrder>>({items:[],page:0,size:20,total:0}),detail=ref<WorkOrder|null>(null)
let revision=0
async function load(){
 const ticket=++revision;error.value='';data.value={items:[],page:page.value,size:20,total:0};detail.value=null
 if(!can('maintenance.read.own')){error.value='当前账号没有本人查询权限。';loading.value=false;return}
 loading.value=true
 try{const id=route.params.id
 if(id){const result=await maintenance<WorkOrder>('/work-orders/'+encodeURIComponent(String(id)));if(ticket===revision)detail.value=result}
 else{const result=await maintenance<Page<WorkOrder>>(`/work-orders?page=${page.value}&size=20`);if(ticket===revision)data.value=result}}
 catch(e){if(ticket===revision)error.value=maintenanceMessage(e)}finally{if(ticket===revision)loading.value=false}
}
watch([()=>route.params.id,()=>session.user?.id,()=>can('maintenance.read.own')],()=>{page.value=0;void load()},{immediate:true})
watch(page,()=>void load());onUnmounted(()=>revision++)
const time=(value:string)=>new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'})
</script>
<template><div class="page-heading"><div><h1>{{route.params.id?'报修详情':'本人报修'}}</h1><p>仅查询当前登录账号提交的工单。</p></div><RouterLink v-if="can('maintenance.report')" class="button" to="/maintenance/new">新建报修</RouterLink></div><RouterLink v-if="route.params.id" to="/maintenance">返回本人列表</RouterLink><section class="panel"><button class="button secondary" :disabled="loading" @click="load">刷新</button><PageState :loading="loading" :error="error" :empty="!route.params.id&&!data.items.length" empty-text="暂无报修记录" @retry="load"><article v-if="detail" class="form-body"><h2>{{stateName(detail.status)}}</h2><p>工单号：{{detail.id}}</p><p>房间：{{detail.roomId}}</p><p>类型：{{faultNames[detail.faultType]??detail.faultType}}</p><p class="preserve-lines">{{detail.description}}</p><p>创建时间：{{time(detail.createdAt)}}</p><p v-if="['IN_PROGRESS','PENDING_ACCEPTANCE','COMPLETED'].includes(detail.status)" role="status">座位限制或恢复尚未获得 M1 确认，验收完成不代表座位已恢复。</p><RepairActions :order="detail" mode="accept" @done="load"/><WorkOrderHistory :entries="detail.history??[]"/></article><template v-else><div class="notice-list"><article v-for="item in data.items" :key="item.id" class="notice"><div><RouterLink :to="'/maintenance/'+item.id">{{item.id}}</RouterLink><p>{{stateName(item.status)}} · {{faultNames[item.faultType]??item.faultType}}</p><p class="preserve-lines">{{item.description}}</p><time>{{time(item.createdAt)}}</time></div></article></div></template></PageState><Pagination v-if="!route.params.id&&!loading&&!error" :page="page" :total="data.total" :size="20" @change="page=$event"/></section></template>
