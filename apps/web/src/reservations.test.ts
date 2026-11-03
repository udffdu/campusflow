import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {ApiError} from './api'
import {checkInReservation,clearPendingReservations,leaveSeat,pendingFor,savePending,shanghaiDateRange,submitReservation,usageSession,type PendingReservation} from './reservations'
const p:PendingReservation={userId:'u',key:'request-key',intent:{roomId:'r',seatId:'s',startAt:'2026-10-29T01:00:00Z',endAt:'2026-10-29T02:00:00Z'},seatCode:'S1',roomCode:'A101'}
beforeEach(()=>sessionStorage.clear());afterEach(()=>vi.unstubAllGlobals())
it('converts inclusive Shanghai calendar dates to one half-open UTC range',()=>{
  expect(shanghaiDateRange('2026-10-30','2026-10-31')).toEqual({from:'2026-10-29T16:00:00.000Z',to:'2026-10-31T16:00:00.000Z'})
  expect(()=>shanghaiDateRange('2026-10-31','2026-10-30')).toThrow('结束日期不能早于开始日期')
  expect(()=>shanghaiDateRange('2026-02-30','2026-03-01')).toThrow('请选择有效日期')
})
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
it('checks in with only reservation id, daily code and the separate M1 CSRF token',async()=>{
  const checked={id:'booking',...p.intent,status:'CHECKED_IN',version:2,resourceVersion:1,stage:'PERSONAL',productionReady:false,checkedInAt:'2026-10-29T01:00:00Z',completedAt:null,serverTime:'2026-10-29T01:05:00Z'}
  const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({token:'check-token'}))).mockResolvedValueOnce(new Response(JSON.stringify(checked)));vi.stubGlobal('fetch',fetch)
  await expect(checkInReservation('booking','012345')).resolves.toMatchObject({status:'CHECKED_IN',version:2})
  expect(fetch.mock.calls[1]).toEqual(['/api/m1/v1/reservations/booking/check-in',expect.objectContaining({method:'POST',headers:{'Content-Type':'application/json','X-M1-CSRF-Token':'check-token'},body:'{"code":"012345"}'})])
  expect(fetch.mock.calls[1]![1]!.body).not.toContain('userId')
})
it('rejects malformed codes before any network request',async()=>{
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch)
  await expect(checkInReservation('booking','12345')).rejects.toMatchObject({code:'CHECK_IN_CODE_INVALID'});expect(fetch).not.toHaveBeenCalled()
})
it('reads the persisted usage clock and leaves the seat with only the reservation id',async()=>{
  const usage={id:'booking',...p.intent,status:'CHECKED_IN',version:2,resourceVersion:1,stage:'PERSONAL',productionReady:false,checkedInAt:'2026-10-30T01:00:00Z',completedAt:null,serverTime:'2026-10-30T01:10:00Z'}
  const completed={...usage,status:'COMPLETED',version:3,completedAt:'2026-10-30T01:20:00Z',serverTime:'2026-10-30T01:20:00Z',idempotent:false}
  const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(usage))).mockResolvedValueOnce(new Response(JSON.stringify({token:'leave-token'}))).mockResolvedValueOnce(new Response(JSON.stringify(completed)));vi.stubGlobal('fetch',fetch)
  await expect(usageSession('booking')).resolves.toMatchObject({status:'CHECKED_IN',completedAt:null})
  await expect(leaveSeat('booking')).resolves.toMatchObject({status:'COMPLETED',idempotent:false})
  expect(fetch.mock.calls[2]).toEqual(['/api/m1/v1/reservations/booking/leave-seat',expect.objectContaining({method:'POST',headers:{'X-M1-CSRF-Token':'leave-token'}})])
})
