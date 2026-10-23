<script setup lang="ts">
import Icon from './Icon.vue'
defineProps<{loading?:boolean;error?:string;empty?:boolean;emptyText?:string}>(); defineEmits<{retry:[]}>()
</script>
<template>
  <div v-if="error" class="state error-state" role="alert"><Icon name="refresh" :size="28"/><strong>暂时无法加载</strong><p>{{error}}</p><button class="button secondary" @click="$emit('retry')">重新加载</button></div>
  <div v-else-if="loading" class="state" role="status"><span class="spinner"/><p>正在加载…</p></div>
  <div v-else-if="empty" class="state"><Icon name="space" :size="32"/><strong>{{emptyText ?? '暂无记录'}}</strong><p>新的记录会显示在这里。</p></div>
  <slot v-else/>
</template>
