<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,watch} from 'vue'
import {useRoute} from 'vue-router'
import {ApiError,message} from '../api'
import {m1Get} from '../m1Api'
import type {RoomCheckInCode} from '../reservations'
const route=useRoute();const data=ref<RoomCheckInCode>();const error=ref('');const loading=ref(false);const now=ref(Date.now());let loadingFor=''
function valid(value:RoomCheckInCode){return value&&value.roomId===route.params.roomId&&typeof value.roomName==='string'&&typeof value.roomCode==='string'&&/^[0-9]{6}$/.test(value.code)&&/^\d{4}-\d{2}-\d{2}$/.test(value.validDate)&&Number.isFinite(Date.parse(value.expiresAt))&&value.timeZone==='Asia/Shanghai'}
async function load(){const room=String(route.params.roomId??'');if(!room||loadingFor===room)return;loadingFor=room;loading.value=true;error.value='';try{const value=await m1Get<RoomCheckInCode>(`/rooms/${encodeURIComponent(room)}/check-in-code`);if(!valid(value))throw new Error('签到码响应不完整，请刷新重试');data.value=value}catch(e){data.value=undefined;error.value=e instanceof ApiError&&(e.status===0||e.status>=500)?'签到码服务不可用，请确认 M1 服务已启动后重试':message(e)}finally{loading.value=false;loadingFor=''}}
const remaining=computed(()=>{if(!data.value)return'';const seconds=Math.max(0,Math.ceil((Date.parse(data.value.expiresAt)-now.value)/1000));const h=Math.floor(seconds/3600),m=Math.floor(seconds%3600/60),s=seconds%60;return `${String(h).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`})
let timer:ReturnType<typeof setInterval>
onMounted(()=>{void load();timer=setInterval(()=>{now.value=Date.now();if(data.value&&now.value>=Date.parse(data.value.expiresAt)){data.value=undefined;void load()}},1000)})
watch(()=>route.params.roomId,()=>{data.value=undefined;error.value='';void load()})
onUnmounted(()=>clearInterval(timer))
</script>
<template><div class="display-page"><div class="page-heading"><div><div class="eyebrow">M1 / ROOM CHECK-IN</div><h1>教室签到码</h1><p>签到码按上海自然日轮换，仅供本院系授权管理页面展示。</p></div><button class="button secondary" :disabled="loading" @click="load">刷新</button></div><p v-if="loading" role="status">正在读取当日签到码…</p><p v-if="error" role="alert" class="form-error">{{error}}</p><section v-if="data" class="panel code-panel" aria-live="polite"><span class="room-label">{{data.roomCode}} · {{data.roomName}}</span><strong class="daily-code" aria-label="六位签到码">{{data.code}}</strong><div class="code-meta"><span>有效日期：{{data.validDate}}（上海）</span><span>距离轮换：{{remaining}}</span></div><p>当前体验阶段不限制签到时间；学生在“我的预约”输入正确的当日教室码后进入使用状态。</p></section></div></template>
<style scoped>.display-page{max-width:980px;margin:0 auto}.code-panel{padding:50px;text-align:center;background:#f8faf5}.room-label{font-size:18px;color:#647360}.daily-code{display:block;font:600 clamp(58px,11vw,110px)/1.25 "SFMono-Regular",Consolas,monospace;letter-spacing:.18em;color:#1d4738;margin:35px 0 25px;padding-left:.18em}.code-meta{display:flex;justify-content:center;gap:28px;color:#6c805d;font-size:13px}.code-panel p{margin-top:32px;color:#8a9584;line-height:1.8}@media(max-width:700px){.code-panel{padding:35px 18px}.daily-code{letter-spacing:.1em;padding-left:.1em}.code-meta{display:grid;gap:8px}}</style>
