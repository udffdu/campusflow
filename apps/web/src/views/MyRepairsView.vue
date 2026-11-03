<script setup lang="ts">
import {ref,watch,onUnmounted} from 'vue'
import {useRoute} from 'vue-router'
import {can,session} from '../session'
import {maintenance,maintenanceMessage,stateName,type WorkOrder} from '../maintenance'
import type {Page} from '../types'
import PageState from '../components/PageState.vue'
import Pagination from '../components/Pagination.vue'
import WorkOrderHistory from '../components/WorkOrderHistory.vue'
import RepairActions from '../components/RepairActions.vue'
const route=useRoute(),loading=ref(false),error=ref(''),page=ref(0),detail=ref<WorkOrder|null>(null),data=ref<Page<WorkOrder>>({items:[],total:0,page:0,size:20})
let revision=0
async function load(){
 const ticket=++revision;error.value='';detail.value=null;data.value={items:[],total:0,page:page.value,size:20}
 if(!can('maintenance.repair')){loading.value=false;error.value='当前账号没有维修权限。';return}
 loading.value=true
 try{if(route.params.id){const value=await maintenance<WorkOrder>('/repair/work-orders/'+encodeURIComponent(String(route.params.id)));if(ticket===revision)detail.value=value}
 else{const value=await maintenance<Page<WorkOrder>>(`/repair/work-orders?page=${page.value}&size=20`);if(ticket===revision)data.value=value}}
 catch(e){if(ticket===revision)error.value=maintenanceMessage(e)}finally{if(ticket===revision)loading.value=false}
}
watch([()=>route.params.id,()=>session.user?.id,()=>can('maintenance.repair')],()=>{page.value=0;void load()},{immediate:true})
watch(page,()=>void load());onUnmounted(()=>revision++)
</script>
<template><div class="page-heading"><div><h1>{{route.params.id?'维修工单详情':'我的维修任务'}}</h1><p>仅显示派给当前账号的任务。</p></div></div><RouterLink v-if="route.params.id" to="/repairs">返回任务列表</RouterLink><section class="panel"><button class="button secondary" :disabled="loading" @click="load">刷新</button><PageState :loading="loading" :error="error" :empty="!route.params.id&&!data.items.length" empty-text="暂无维修任务" @retry="load"><article v-if="detail" class="form-body"><h2>{{stateName(detail.status)}}</h2><p>工单：{{detail.id}}</p><p>房间：{{detail.roomId}}</p><p class="preserve-lines">{{detail.description}}</p><p v-if="detail.status!=='ASSIGNED'" role="status">座位限制或恢复尚未获得 M1 确认；工单状态不代表座位已下架或恢复。</p><RepairActions :order="detail" mode="repair" @done="load"/><WorkOrderHistory :entries="detail.history??[]"/></article><div v-else class="notice-list"><article v-for="item in data.items" :key="item.id" class="notice"><div><RouterLink :to="'/repairs/'+item.id">{{item.id}}</RouterLink><p>{{stateName(item.status)}}</p><p class="preserve-lines">{{item.description}}</p></div></article></div></PageState><Pagination v-if="!route.params.id&&!loading&&!error" :page="page" :total="data.total" :size="20" @change="page=$event"/></section></template>
