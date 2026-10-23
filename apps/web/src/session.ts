import { reactive } from 'vue'
import { api, ApiError } from './api'
import type { Identity } from './types'
export const session = reactive({ user: null as Identity|null, ready: false, unread: 0 })
export function can(permission: string) { return session.user?.permissions.includes(permission) ?? false }
export function inScope(departmentId: string|null) { return session.user?.roles.includes('ADMIN') || (!!departmentId && session.user?.departmentId === departmentId) }
export async function loadSession() {
  try { session.user = await api<Identity>('/auth/me') }
  catch(error) { if (!(error instanceof ApiError) || error.status !== 401) throw error; session.user = null }
  session.ready = true
}
export async function refreshUnread() {
  if (!session.user) return
  const result = await api<{count:number}>('/notifications/unread'); session.unread = result.count
}
