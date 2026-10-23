<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import Icon from './Icon.vue'
defineProps<{title:string}>(); const emit = defineEmits<{close:[]}>()
const dialog = ref<HTMLDialogElement>(); let previous: Element|null = null
onMounted(() => { previous = document.activeElement; dialog.value?.showModal() })
onUnmounted(() => { if (previous instanceof HTMLElement) previous.focus() })
</script>
<template><dialog ref="dialog" class="modal" aria-labelledby="modal-title" @cancel.prevent="emit('close')"><div class="modal-head"><h2 id="modal-title">{{title}}</h2><button type="button" class="icon-button" aria-label="关闭" @click="emit('close')"><Icon name="close"/></button></div><slot/></dialog></template>
