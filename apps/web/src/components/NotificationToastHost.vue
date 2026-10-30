<script setup lang="ts">
import {onMounted,onUnmounted,ref,watch} from 'vue'
import {useRouter} from 'vue-router'
import {api} from '../api'
import {refreshUnread,session} from '../session'
import type {Notice,Page} from '../types'
import Icon from './Icon.vue'

const router=useRouter();const toasts=ref<Notice[]>([])
let seen=new Set<string>();let baselineUser='';let checking=false;let poll:ReturnType<typeof setInterval>|undefined
const timers=new Map<string,ReturnType<typeof setTimeout>>()

function dismiss(id:string){toasts.value=toasts.value.filter(item=>item.id!==id);const timer=timers.get(id);if(timer)clearTimeout(timer);timers.delete(id)}
function clear(){for(const timer of timers.values())clearTimeout(timer);timers.clear();toasts.value=[];seen=new Set();baselineUser=''}
function show(notice:Notice){
  if(toasts.value.some(item=>item.id===notice.id))return
  toasts.value=[notice,...toasts.value]
  timers.set(notice.id,setTimeout(()=>dismiss(notice.id),10000))
}
async function check(){
  const userId=session.user?.id;if(!userId||checking)return;checking=true
  try{
    const result=await api<Page<Notice>>('/notifications?page=0&size=10')
    if(session.user?.id!==userId)return
    if(baselineUser!==userId){baselineUser=userId;seen=new Set(result.items.map(item=>item.id));return}
    const incoming=result.items.filter(item=>!seen.has(item.id)&&!item.readAt)
    result.items.forEach(item=>seen.add(item.id))
    if(incoming.length){incoming.reverse().forEach(show);void refreshUnread().catch(()=>{})}
  }catch{}finally{checking=false}
}
async function open(id:string){dismiss(id);await router.push('/notifications')}
watch(()=>session.user?.id,()=>{clear();if(session.user)void check()},{immediate:true})
onMounted(()=>{poll=setInterval(()=>void check(),3000)})
onUnmounted(()=>{if(poll)clearInterval(poll);clear()})
</script>

<template><Teleport to="body"><section v-if="toasts.length" class="notification-toast-stack" aria-live="polite" aria-label="新通知提醒"><article v-for="notice in toasts" :key="notice.id" class="notification-toast" role="status"><span class="notification-toast-icon"><Icon name="bell" :size="18"/></span><div class="notification-toast-content"><div class="notification-toast-heading"><div><small>新站内通知</small><h2>{{notice.title}}</h2></div><button class="icon-button" :aria-label="`关闭通知：${notice.title}`" @click="dismiss(notice.id)"><Icon name="close" :size="16"/></button></div><p>{{notice.body}}</p><button class="text-link" @click="open(notice.id)">查看通知中心<Icon name="arrow" :size="14"/></button></div></article></section></Teleport></template>
