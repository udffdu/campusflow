<script setup lang="ts">
import {onMounted,ref,watch} from 'vue'
import {api,message} from '../api'
import {refreshUnread,session} from '../session'
import {dateTime,type Notice} from '../types'
import {useList} from '../useList'
import Icon from '../components/Icon.vue'
import PageState from '../components/PageState.vue'
import Pagination from '../components/Pagination.vue'
const {data,loading,error,load}=useList<Notice>();const page=ref(0);const actionError=ref('');const busy=ref(false)
function reload(){return load(`/notifications?page=${page.value}&size=20`)};watch(page,reload);onMounted(()=>{void reload();void refreshUnread().catch(()=>{})})
async function read(id?:string){busy.value=true;actionError.value='';try{await api(id?`/notifications/${id}/read`:'/notifications/read-all','POST');await Promise.all([reload(),refreshUnread()])}catch(e){actionError.value=message(e)}finally{busy.value=false}}
</script>
<template><div class="page-heading"><div><div class="eyebrow">INBOX / YOUR UPDATES</div><h1>通知中心 <span v-if="session.unread" class="count-pill">{{session.unread}} 条未读</span></h1><p>与你有关的动态，在这里有序汇集。</p></div><button class="button secondary" :disabled="busy||!session.unread" @click="read()"><Icon name="check" :size="17"/>全部标为已读</button></div><p v-if="actionError" role="alert" class="form-error">{{actionError}}</p><section class="panel"><div class="panel-heading"><h2>我的站内信</h2><button class="text-link" @click="reload"><Icon name="refresh" :size="16"/>刷新</button></div><PageState :loading="loading" :error="error" :empty="!data.items.length" empty-text="暂时没有新的通知" @retry="reload"><div class="notice-list"><article v-for="notice in data.items" :key="notice.id" class="notice" :class="{unread:!notice.readAt}"><span class="notice-icon"><Icon name="bell"/></span><div class="notice-content"><div class="notice-heading"><h3>{{notice.title}}<i v-if="!notice.readAt" class="unread-dot"/></h3><time>{{dateTime(notice.receivedAt)}}</time></div><p class="notice-message preserve-lines">{{notice.body}}</p><div class="notice-meta"><span>来源 {{notice.source}}<template v-if="notice.objectType==='SEAT_RESERVATION'"> · 座位预约提醒</template></span><span class="mono">{{notice.objectType==='SEAT_RESERVATION'?'预约编号':'对象编号'}} {{notice.objectId}}</span><button v-if="!notice.readAt" :disabled="busy" class="text-link" @click="read(notice.id)">标为已读</button><span v-else>已读</span></div></div></article></div><Pagination :page="page" :total="data.total" :size="20" @change="page=$event"/></PageState></section></template>
