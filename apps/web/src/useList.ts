import { ref } from 'vue'
import { api, message } from './api'
import type { Page } from './types'
export function useList<T>() {
  const data = ref<Page<T>>({items:[],page:0,size:20,total:0})
  const loading = ref(false); const error = ref(''); let serial = 0
  async function load(path: string) {
    const current = ++serial; loading.value = true; error.value = ''
    try { const result = await api<Page<T>>(path); if (current === serial) data.value = result as typeof data.value }
    catch(e) { if (current === serial) error.value = message(e) }
    finally { if (current === serial) loading.value = false }
  }
  return {data,loading,error,load}
}
