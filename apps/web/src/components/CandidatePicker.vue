<script setup lang="ts">
import {ref,watch,onUnmounted} from 'vue'
import {can,session} from '../session'
import {maintenance,maintenanceMessage} from '../maintenance'
import {ApiError} from '../api'
import PageState from './PageState.vue'
import Pagination from './Pagination.vue'
interface Candidate {id:string;displayName:string;active:boolean;qualification:string;globalScope:boolean;departmentIds:string[]}
const props=defineProps<{orderId:string}>(),emit=defineEmits<{select:[Candidate|null]}>()
const query=ref(''),search=ref(''),page=ref(0),items=ref<Candidate[]>([]),total=ref(0),loading=ref(false),error=ref(''),selected=ref<Candidate|null>(null)
let revision=0
async function load(){const ticket=++revision;selected.value=null;emit('select',null);items.value=[];total.value=0;error.value=''
 if(!can('maintenance.dispatch')){error.value='当前账号没有选择维修人员的权限。';loading.value=false;return}loading.value=true
 try{const result=await maintenance<{items:Candidate[];total:number}>(`/management/work-orders/${encodeURIComponent(props.orderId)}/candidates?page=${page.value}&size=20&q=${encodeURIComponent(search.value)}`);if(ticket===revision){items.value=result.items;total.value=result.total}}
 catch(e){if(ticket===revision)error.value=e instanceof ApiError&&e.code==='D_CANDIDATES_NOT_READY'?'维修人员服务尚未接入，请稍后重试。':maintenanceMessage(e)}finally{if(ticket===revision)loading.value=false}}
function find(){search.value=query.value.trim();if(page.value!==0)page.value=0;else void load()}
watch([()=>props.orderId,()=>session.user?.id,()=>can('maintenance.dispatch')],()=>{page.value=0;void load()},{immediate:true});watch(page,()=>void load());onUnmounted(()=>revision++)
function choose(person:Candidate){if(!loading.value&&can('maintenance.dispatch')&&person.active&&person.qualification==='maintenance.repair'){selected.value=person;emit('select',person)}}
</script>
<template><section><h3>选择维修人员</h3><form @submit.prevent="find"><label>搜索人员<input v-model="query" maxlength="100" :disabled="!can('maintenance.dispatch')"></label><button class="button secondary" :disabled="loading||!can('maintenance.dispatch')">搜索</button></form><PageState :loading="loading" :error="error" :empty="!items.length" empty-text="没有符合条件的维修人员，暂不能派单" @retry="load"><ul><li v-for="person in items" :key="person.id"><label><input type="radio" name="candidate" :checked="selected?.id===person.id" :disabled="!person.active||person.qualification!=='maintenance.repair'" @change="choose(person)">{{person.displayName}} · {{person.active?'已启用':'已停用'}} · 维修资格 · {{person.globalScope?'全局服务':person.departmentIds.join('、')}}</label></li></ul></PageState><Pagination v-if="!loading&&!error" :page="page" :total="total" :size="20" @change="page=$event"/><p v-if="selected" role="status">已选择：{{selected.displayName}}。提交派单时将再次校验资格。</p><p>此处仅选择人员，不执行派单。</p></section></template>
