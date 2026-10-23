<script setup lang="ts">
defineProps<{page:number;total:number;size:number}>(); defineEmits<{change:[number]}>()
</script>
<template><div class="pagination"><span>共 {{total}} 条 · 第 {{page+1}} / {{Math.max(1,Math.ceil(total/size))}} 页</span><div><button class="button small secondary" :disabled="page===0" @click="$emit('change',page-1)">上一页</button><button class="button small secondary" :disabled="(page+1)*size>=total" @click="$emit('change',page+1)">下一页</button></div></div></template>
