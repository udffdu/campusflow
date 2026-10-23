import { beforeEach, describe, expect, it, vi } from 'vitest'
describe('API security and errors',()=>{
  beforeEach(()=>{vi.resetModules();vi.unstubAllGlobals()})
  it('gets a CSRF token and includes it on writes, with same-origin cookies',async()=>{
    const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({token:'test-csrf'}))).mockResolvedValueOnce(new Response(JSON.stringify({ok:true})))
    vi.stubGlobal('fetch',fetch);const {api}=await import('./api');await api('/auth/login','POST',{username:'member',password:'test-only'})
    expect(fetch.mock.calls[0]![0]).toBe('/api/v1/auth/csrf')
    expect(fetch.mock.calls[1]![1]).toMatchObject({credentials:'same-origin',headers:{'X-XSRF-TOKEN':'test-csrf'},method:'POST'})
    expect(localStorage.length).toBe(0)
  })
  it('notifies the shell on an expired session but preserves a failed login error',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify({code:'UNAUTHENTICATED',message:'请先登录'}),{status:401})))
    const listener=vi.fn();window.addEventListener('session-expired',listener);const {api}=await import('./api')
    await expect(api('/users')).rejects.toMatchObject({status:401,code:'UNAUTHENTICATED'});expect(listener).toHaveBeenCalledTimes(1)
    window.removeEventListener('session-expired',listener)
  })
  it('preserves conflict messages and trace IDs without treating them as success',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify({code:'CONFLICT',message:'已被其他人修改',requestId:'trace-1'}),{status:409})))
    const {api}=await import('./api');await expect(api('/resources')).rejects.toMatchObject({status:409,message:'已被其他人修改',requestId:'trace-1'})
  })
  it('refreshes the CSRF token after login before the next protected write',async()=>{
    const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({token:'before-login'}))).mockResolvedValueOnce(new Response(JSON.stringify({id:'user'})))
      .mockResolvedValueOnce(new Response(JSON.stringify({token:'after-login'}))).mockResolvedValueOnce(new Response(JSON.stringify({ok:true})))
    vi.stubGlobal('fetch',fetch);const {api}=await import('./api');await api('/auth/login','POST',{username:'member',password:'test-only'});await api('/notifications/read-all','POST')
    expect(fetch.mock.calls[2]![0]).toBe('/api/v1/auth/csrf');expect(fetch.mock.calls[3]![1].headers['X-XSRF-TOKEN']).toBe('after-login')
  })
})
