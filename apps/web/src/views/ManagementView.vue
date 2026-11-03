<script setup lang="ts">
import {ref,watch,onUnmounted} from 'vue'
import {useRoute} from 'vue-router'
import {can,session} from '../session'
import {maintenance,maintenanceMessage,stateName,faultNames} from '../maintenance'
import type {Page} from '../types'
import DispatchPanel from '../components/DispatchPanel.vue'
import PageState from '../components/PageState.vue'
import Pagination from '../components/Pagination.vue'
interface Item {id:string;roomId:string;faultType:string;description:string;status:string;createdAt:string;assigneeId:string|null;version:number}
const route=useRoute(),page=ref(0),status=ref(''),loading=ref(false),error=ref(''),detail=ref<Item|null>(null),data=ref<Page<Item>>({items:[],total:0,page:0,size:20})
let revision=0
async function load(){
 const ticket=++revision;error.value='';detail.value=null;data.value={items:[],total:0,page:page.value,size:20}
 if(!can('maintenance.read.scope')){loading.value=false;error.value='当前账号没有管理查询权限。';return}
 loading.value=true
 try{
  if(route.params.id){const value=await maintenance<Item>('/management/work-orders/'+encodeURIComponent(String(route.params.id)));if(ticket===revision)detail.value=value}
  else{const value=await maintenance<Page<Item>>(`/management/work-orders?page=${page.value}&size=20${status.value?'&status='+encodeURIComponent(status.value):''}`);if(ticket===revision)data.value=value}
 }catch(e){if(ticket===revision)error.value=maintenanceMessage(e).replace('不属于本人','不在授权范围内')}
 finally{if(ticket===revision)loading.value=false}
}
function filter(){if(page.value!==0)page.value=0;else void load()}
watch([()=>route.params.id,()=>session.user?.id,()=>can('maintenance.read.scope')],()=>{page.value=0;void load()},{immediate:true})
watch(page,()=>void load());onUnmounted(()=>revision++)
const time=(value:string)=>new Date(value).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'})
</script>
<template><div class="page-heading"><div><h1>{{route.params.id?'管理工单详情':'管理工单'}}</h1><p>仅显示当前账号获授权范围内的工单。</p></div></div><RouterLink v-if="route.params.id" to="/maintenance-management">返回管理列表</RouterLink><section class="panel"><div class="panel-heading"><label v-if="!route.params.id">状态筛选<select v-model="status" :disabled="!can('maintenance.read.scope')" @change="filter"><option value="">全部状态</option><option value="PENDING_DISPATCH">待派单</option><option value="ASSIGNED">已派单</option></select></label><button class="button secondary" :disabled="loading||!can('maintenance.read.scope')" @click="load">刷新</button></div><PageState :loading="loading" :error="error" :empty="!route.params.id&&!data.items.length" empty-text="当前范围内暂无符合条件的工单" @retry="load"><article v-if="detail" class="form-body"><h2>{{stateName(detail.status)}}</h2><p>工单编号：{{detail.id}}</p><p>房间 ID：{{detail.roomId}}</p><p>故障类型：{{faultNames[detail.faultType]}}</p><p class="preserve-lines">{{detail.description}}</p><p>报修时间：{{time(detail.createdAt)}}</p><p>当前维修人员：{{detail.assigneeId??'未派单'}}</p><DispatchPanel v-if="detail.status==='PENDING_DISPATCH'&&can('maintenance.dispatch')" :order-id="detail.id" :version="detail.version" @done="load"/></article><div v-else class="notice-list"><article v-for="item in data.items" :key="item.id" class="notice"><div><RouterLink :to="'/maintenance-management/'+item.id">{{item.id}}</RouterLink><p>房间 ID：{{item.roomId}}</p><p>{{stateName(item.status)}} · {{faultNames[item.faultType]}}</p><p class="preserve-lines">{{item.description}}</p><p>报修时间：{{time(item.createdAt)}}</p><p>当前维修人员：{{item.assigneeId??'未派单'}}</p></div></article></div></PageState><Pagination v-if="!route.params.id&&!loading&&!error" :page="page" :total="data.total" :size="20" @change="page=$event"/></section></template>
