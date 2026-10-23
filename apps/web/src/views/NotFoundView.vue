<script setup lang="ts">import {useRoute} from 'vue-router';import Icon from '../components/Icon.vue';const route=useRoute()</script>
<template><div class="state large-state"><Icon name="shield" :size="42"/><h1>{{route.path==='/forbidden'?'当前账号无权访问':'页面不存在'}}</h1><p>可以返回工作台，从可用的服务入口继续。</p><RouterLink to="/" class="button">返回工作台</RouterLink></div></template>
