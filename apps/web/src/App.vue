<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, message } from './api'
import { can, refreshUnread, session } from './session'
import { roleNames } from './types'
import Icon from './components/Icon.vue'
import {m1Get} from './m1Api'
import {clearPendingReservations,type Capabilities} from './reservations'
const demoAvailable=ref(false)
watch(()=>session.user?.id,async (id,previous)=>{if(previous&&previous!==id)clearPendingReservations();demoAvailable.value=false;if(id&&can('seats.availability.read'))try{const caps=await m1Get<Capabilities>('/reservation-capabilities');if(session.user?.id===id)demoAvailable.value=caps.canCreateDemoReservation}catch{}},{immediate:true})
import Modal from './components/Modal.vue'
const route = useRoute(); const router = useRouter(); const menuOpen = ref(false)
const passwordOpen = ref(false); const currentPassword = ref(''); const newPassword = ref(''); const error = ref(''); const busy = ref(false)
const navigation = computed(() => [
  {path:'/',name:'工作台',icon:'grid',show:true}, {path:'/resources',name:'空间资源',icon:'space',show:can('spaces.read')},
  {path:'/seat-query',name:'座位查询',icon:'chair',show:can('seats.availability.read')},
  {path:'/my-reservations',name:'我的预约',icon:'chair',show:can('seats.reservations.read.own')},
  {path:'/seat-reservation-demo',name:'个人预约演示',icon:'chair',show:demoAvailable.value},
  {path:'/notifications',name:'通知中心',icon:'bell',show:true},
  {path:'/users',name:'账号与权限',icon:'users',show:can('users.manage')},
  {path:'/deliveries',name:'通知投递',icon:'send',show:can('notifications.manage')},
  {path:'/audit',name:'操作审计',icon:'audit',show:can('audit.read')},
].filter(item => item.show))
const date = new Intl.DateTimeFormat('zh-CN',{month:'long',day:'numeric',weekday:'long'}).format(new Date())
async function logout() { busy.value=true; error.value=''; try { await api('/auth/logout','POST'); clearPendingReservations(); session.user=null; session.unread=0; await router.replace('/login') } catch(e) {error.value=message(e)} finally {busy.value=false} }
async function changePassword() { busy.value=true;error.value=''; try { await api('/auth/password','POST',{currentPassword:currentPassword.value,newPassword:newPassword.value}); clearPendingReservations();passwordOpen.value=false;currentPassword.value='';newPassword.value='';session.user=null;await router.replace('/login?changed=1') } catch(e) {error.value=message(e)} finally {busy.value=false} }
let poll: ReturnType<typeof setInterval>
onMounted(() => { poll=setInterval(() => { if(session.user) void refreshUnread().catch(()=>{}) },30000) })
onUnmounted(() => clearInterval(poll))
</script>
<template>
  <RouterView v-if="route.path==='/login'"/>
  <div v-else-if="session.user" class="app-shell">
    <aside class="sidebar" :class="{open:menuOpen}">
      <RouterLink to="/" class="brand"><span class="brand-mark">c<span>f</span></span><span>CampusFlow<small>校园资源协同平台</small></span></RouterLink>
      <div class="workspace-label"><span class="live-dot"/> 校园公共服务 <span class="workspace-tag">D</span></div>
      <div class="nav-caption">工作空间</div>
      <nav aria-label="主导航"><RouterLink v-for="item in navigation" :key="item.path" :to="item.path" :class="{active:route.path===item.path}" @click="menuOpen=false"><Icon :name="item.icon"/><span>{{item.name}}</span><span v-if="item.path==='/notifications'&&session.unread" class="nav-badge">{{session.unread}}</span></RouterLink></nav>
      <div class="sidebar-note"><Icon name="shield" :size="23"/><strong>连接校园的每一处空间</strong><p>让资源有序，让协作发生。</p><span>CampusFlow / 公共服务</span></div>
      <div class="sidebar-footer"><span class="avatar">{{session.user.displayName.slice(0,1)}}</span><div><strong>{{session.user.displayName}}</strong><small>{{roleNames[session.user.roles[0]!] ?? '成员'}}</small></div><button class="icon-button" aria-label="退出登录" :disabled="busy" @click="logout"><Icon name="logout" :size="18"/></button></div>
    </aside>
    <div v-if="menuOpen" class="mobile-backdrop" @click="menuOpen=false"/>
    <div class="main-area">
      <header class="topbar"><div class="breadcrumb"><button class="icon-button mobile-menu" aria-label="打开导航" @click="menuOpen=!menuOpen"><Icon name="menu"/></button><span>公共服务</span><Icon name="chevron" :size="14"/><strong>{{route.meta.title}}</strong></div><div class="topbar-actions"><span class="topbar-date">{{date}}</span><RouterLink to="/notifications" class="icon-button notification-button" aria-label="打开通知中心"><Icon name="bell"/><i v-if="session.unread"/></RouterLink><button class="icon-button" aria-label="修改密码" @click="passwordOpen=true;error='' "><Icon name="key"/></button><span class="avatar small-avatar">{{session.user.displayName.slice(0,1)}}</span></div></header>
      <main id="main-content"><p v-if="error&&!passwordOpen" class="form-error" role="alert">{{error}}</p><RouterView/></main>
      <footer class="page-footer"><span>CampusFlow · 校园资源协同平台</span><span>有序连接，共享校园</span></footer>
    </div>
    <Modal v-if="passwordOpen" title="修改登录密码" @close="passwordOpen=false;currentPassword='';newPassword='' "><form class="form-body" @submit.prevent="changePassword"><p class="muted">修改后，所有设备的会话将退出，请重新登录。</p><label>当前密码<input v-model="currentPassword" type="password" autocomplete="current-password" required maxlength="72"></label><label>新密码<input v-model="newPassword" type="password" autocomplete="new-password" required minlength="12" maxlength="72"><small>至少 12 个字符，中文等多字节字符总计不超过 72 字节。</small></label><p v-if="error" role="alert" class="form-error">{{error}}</p><div class="form-actions"><button type="button" class="button secondary" @click="passwordOpen=false">取消</button><button class="button" :disabled="busy">{{busy?'正在保存…':'保存并重新登录'}}</button></div></form></Modal>
  </div>
</template>
