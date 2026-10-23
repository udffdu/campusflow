<script setup lang="ts">
defineProps<{ name: string; size?: number }>()
const paths: Record<string,string> = {
  grid:'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  space:'M3 21V5l9-3v19 M12 8h9v13 M6 7v2 M6 12v2 M6 17v2 M16 11v2 M16 16v2 M2 21h20',
  users:'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M16 3a4 4 0 0 1 0 8 M22 21v-2a4 4 0 0 0-3-3.87 M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
  bell:'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4',
  send:'m22 2-7 20-4-9-9-4 20-7 M22 2 11 13',
  audit:'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M8 13h8 M8 17h5',
  arrow:'M4 12h16 M14 6l6 6-6 6', plus:'M12 5v14 M5 12h14', search:'M21 21l-4.3-4.3 M19 11a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  logout:'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4 M16 17l5-5-5-5 M21 12H9',
  shield:'m12 2 9 4v6c0 5-9 10-9 10S3 17 3 12V6z M8 12l3 3 5-6',
  chevron:'m9 5 7 7-7 7', check:'m5 12 4 4L19 6', close:'m6 6 12 12 M6 18 18 6',
  chair:'M5 14h14v4H5z M7 14V4h10v10 M7 18v3 M17 18v3 M3 10v6 M21 10v6',
  menu:'M4 6h16 M4 12h16 M4 18h16', key:'M15 7a5 5 0 1 1-3-4 M15 7h7 M19 7v4 M22 7v3',
  refresh:'M20 11a8 8 0 0 0-14-5L3 9 M3 3v6h6 M4 13a8 8 0 0 0 14 5l3-3 M21 21v-6h-6',
}
</script>
<template><svg :width="size ?? 20" :height="size ?? 20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] ?? paths.grid"/></svg></template>
