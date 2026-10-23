export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public requestId = '') { super(message) }
}
let csrfToken = ''
export async function csrf() {
  const response = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })
  if (!response.ok) throw new ApiError(response.status, 'CSRF', '无法获取安全令牌，请检查服务连接')
  const result = await response.json(); csrfToken = result.token
}
export async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const write = !['GET','HEAD'].includes(method)
  if (write && !csrfToken) await csrf()
  let response: Response
  try {
    response = await fetch(`/api/v1${path}`, { method, credentials: 'same-origin', headers: { ...(body !== undefined ? {'Content-Type':'application/json'} : {}), ...(write ? {'X-XSRF-TOKEN': csrfToken} : {}) }, ...(body !== undefined ? {body: JSON.stringify(body)} : {}) })
  } catch { throw new ApiError(0, 'NETWORK', '连接失败，请确认服务运行后重试') }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}))
    if (response.status === 401 && path !== '/auth/login') window.dispatchEvent(new Event('session-expired'))
    if (response.status === 403) csrfToken = ''
    throw new ApiError(response.status, problem.code ?? 'REQUEST_FAILED', problem.message ?? '请求失败，请稍后重试', problem.requestId)
  }
  if (['/auth/login','/auth/logout','/auth/password'].includes(path)) csrfToken = ''
  return response.json()
}
export function message(error: unknown) { return error instanceof Error ? error.message : '操作失败，请稍后重试' }
export async function allPages<T>(path: string): Promise<T[]> {
  const items: T[] = []; let page = 0
  for (;;) {
    const result = await api<{items:T[];total:number}>(`${path}${path.includes('?') ? '&' : '?'}page=${page}&size=100`)
    items.push(...result.items)
    if (items.length >= result.total || !result.items.length) return items
    page++
  }
}
