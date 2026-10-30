import { reactive } from 'vue'
import { api, ApiError } from './api'
import type { Identity } from './types'
export const session = reactive({ user: null as Identity|null, ready: false, unread: 0 })
const sessionChannel = typeof window !== 'undefined' && typeof window.BroadcastChannel === 'function'
  ? new window.BroadcastChannel('campusflow-session') : undefined
const sessionListeners = new Set<() => void>()
sessionChannel?.addEventListener('message', event => {
  if (event.data?.type === 'session-changed') sessionListeners.forEach(listener => listener())
})
if (import.meta.hot) import.meta.hot.dispose(() => sessionChannel?.close())

/** Notify other same-origin tabs without putting an identity or credential in browser storage. */
export function announceSessionChange() { sessionChannel?.postMessage({type:'session-changed'}) }
export function onSessionChange(listener:()=>void) {
  sessionListeners.add(listener)
  return () => sessionListeners.delete(listener)
}
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

export function landingPath() { return !can('spaces.read') ? can('seats.availability.read')?'/seat-query':can('seats.reservations.read.own')?'/my-reservations':'/' : '/' }
