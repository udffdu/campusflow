<script setup lang="ts">
import {stateName,type HistoryEntry} from '../maintenance'
defineProps<{entries:HistoryEntry[]}>()
const actions:Record<string,string>={CREATED:'创建工单',ASSIGNED:'首次派单',REPAIR_STARTED:'开始维修',COMPLETION_SUBMITTED:'提交完工',ACCEPTANCE_PASSED:'验收通过',ACCEPTANCE_REJECTED:'验收驳回'}
const time=(value:string)=>new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date(value))
</script>
<template><section aria-label="工单操作历史"><h3>操作历史</h3><p class="muted">以下为本地业务记录，不代表集中审计已送达。</p><p v-if="!entries.length">暂无操作历史</p><ol v-else><li v-for="entry in entries" :key="entry.id"><strong>{{actions[entry.action]??entry.action}}</strong><p>操作者 ID：{{entry.actorId}}</p><p>{{entry.fromStatus?stateName(entry.fromStatus):'无前置状态'}} → {{stateName(entry.toStatus)}}</p><p v-if="entry.assigneeId">当时维修人员 ID：{{entry.assigneeId}}</p><p v-if="entry.remark!==null" class="preserve-lines">说明：{{entry.remark}}</p><time :datetime="entry.occurredAt">{{time(entry.occurredAt)}}（上海时间）</time></li></ol></section></template>
