import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {ApiError} from './api'
import {clearPendingReservations,pendingFor,savePending,submitReservation,type PendingReservation} from './reservations'
const p:PendingReservation={userId:'u',key:'request-key',intent:{roomId:'r',seatId:'s',startAt:'2026-10-29T01:00:00Z',endAt:'2026-10-29T02:00:00Z'},seatCode:'S1',roomCode:'A101'}
beforeEach(()=>sessionStorage.clear());afterEach(()=>vi.unstubAllGlobals())
it('sends separate M1 CSRF and idempotency headers without identity or D credentials',async()=>{
  const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({token:'test-token',headerName:'X-M1-CSRF-Token'}))).mockResolvedValueOnce(new Response(JSON.stringify({id:'booking',...p.intent,status:'CONFIRMED',version:1,resourceVersion:1,stage:'PERSONAL_DEMO',productionReady:false}),{status:201}));vi.stubGlobal('fetch',fetch)
  await submitReservation(p);expect(fetch.mock.calls[1]).toEqual(['/api/m1/v1/reservations',expect.objectContaining({headers:{'Content-Type':'application/json','X-M1-CSRF-Token':'test-token','Idempotency-Key':'request-key'},body:JSON.stringify(p.intent)})])
})
it('does not automatically resend a POST when its response is lost',async()=>{
  const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({token:'test-token'}))).mockRejectedValueOnce(new Error('connection reset'));vi.stubGlobal('fetch',fetch)
  await expect(submitReservation(p)).rejects.toMatchObject({code:'RESULT_UNKNOWN'});expect(fetch).toHaveBeenCalledTimes(2)
})
it('clears only pending drafts on logout/expiry, never stores credentials',()=>{
  sessionStorage.setItem('unrelated','keep');savePending(p);expect(pendingFor('other')).toBeUndefined();clearPendingReservations();expect(pendingFor('u')).toBeUndefined();expect(sessionStorage.getItem('unrelated')).toBe('keep')
})

it('treats a successful but incomplete JSON response as unknown instead of success',async()=>{
  const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({token:'test-token'}))).mockResolvedValueOnce(new Response('{}',{status:201}));vi.stubGlobal('fetch',fetch)
  await expect(submitReservation(p)).rejects.toMatchObject({code:'RESULT_UNKNOWN'})
})
