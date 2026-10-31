import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {ApiError} from '../api'
import MyReservationsView from '../views/MyReservationsView.vue'
import {session} from '../session'
import {savePending} from '../reservations'
const {get,push,checkIn}=vi.hoisted(()=>({get:vi.fn(),push:vi.fn(),checkIn:vi.fn()}))
vi.mock('../m1Api',()=>({m1Get:get}))
vi.mock('vue-router',()=>({useRouter:()=>({push})}))
vi.mock('../reservations',async()=>({...await vi.importActual<typeof import('../reservations')>('../reservations'),checkInReservation:checkIn}))
let wrapper:VueWrapper|undefined
const serverTime='2026-10-29T01:20:00Z'
const record={id:'booking-1',roomId:'r',seatId:'s',startAt:'2026-10-29T01:00:00Z',endAt:'2026-10-29T02:00:00Z',status:'CONFIRMED',version:1,resourceVersion:1,stage:'PERSONAL_DEMO',productionReady:false,checkedInAt:null,completedAt:null,usageDurationSeconds:0,violationReason:null}
const caps={canReadOwn:true,canCreateDemoReservation:true,productionReady:false,stage:'PERSONAL_DEMO'}
const history=(items:unknown[]= [record],nextCursor='')=>({items,size:20,nextCursor,serverTime})
beforeEach(()=>{sessionStorage.clear();get.mockReset();push.mockReset();checkIn.mockReset();session.user={id:'u',username:'student',displayName:'同学',email:'student@example.invalid',departmentId:'d',active:true,roles:['SEAT_USER'],permissions:['seats.reservations.read.own','seats.availability.read','seats.reservations.create']};get.mockImplementation(async(path:string)=>path==='/reservation-capabilities'?caps:history())})
afterEach(()=>{wrapper?.unmount();wrapper=undefined})
async function open(){wrapper=mount(MyReservationsView,{global:{stubs:{RouterLink:true}}});await flushPromises()}
it('reads own records without passing a user identity and opens repeat using only the reservation ID',async()=>{
  await open();expect(get).toHaveBeenCalledWith('/reservations?size=20');expect(wrapper!.text()).toContain('booking-1');expect(wrapper!.text()).toContain('待签到')
  await wrapper!.findAll('button').find(b=>b.text()==='再次预约')!.trigger('click');expect(push).toHaveBeenCalledWith({path:'/seat-reservation-demo',query:{repeatReservationId:'booking-1'}})
})
it('shows an empty result separately from failure and retries reads only',async()=>{
  get.mockImplementation(async(path:string)=>{if(path==='/reservation-capabilities')return caps;throw new Error('历史暂不可用')});await open();expect(wrapper!.text()).toContain('历史暂不可用');expect(wrapper!.text()).not.toContain('当前没有本人的预约记录')
  get.mockResolvedValueOnce(caps).mockResolvedValueOnce(history([]));await wrapper!.findAll('button').find(b=>b.text()==='重试')!.trigger('click');await flushPromises();expect(wrapper!.text()).toContain('当前没有本人的预约记录')
})
it('uses the server cursor for next page and returns to the first page on refresh',async()=>{
  get.mockImplementation(async(path:string)=>path==='/reservation-capabilities'?caps:history([record],path.includes('cursor=')?'':'next-token'));await open()
  await wrapper!.findAll('button').find(b=>b.text()==='下一页')!.trigger('click');await flushPromises();expect(get).toHaveBeenCalledWith('/reservations?size=20&cursor=next-token');expect(wrapper!.text()).toContain('第 2 页')
  await wrapper!.findAll('button').find(b=>b.text()==='刷新列表')!.trigger('click');await flushPromises();expect(wrapper!.text()).toContain('第 1 页');expect(get.mock.calls.at(-1)?.[0]).toBe('/reservations?size=20')
})
it('applies owner-scoped status and inclusive Shanghai date filters on the server',async()=>{
  get.mockImplementation(async(path:string)=>path==='/reservation-capabilities'?caps:path.includes('status=NO_SHOW')?history([]):history())
  await open();await wrapper!.get('select').setValue('NO_SHOW');const dates=wrapper!.findAll('input[type="date"]');await dates[0]!.setValue('2026-10-30');await dates[1]!.setValue('2026-10-31')
  await wrapper!.get('form.history-filters').trigger('submit');await flushPromises()
  expect(get).toHaveBeenCalledWith('/reservations?size=20&status=NO_SHOW&from=2026-10-29T16%3A00%3A00.000Z&to=2026-10-31T16%3A00%3A00.000Z')
  expect(wrapper!.text()).toContain('当前筛选条件下没有预约记录')
  await wrapper!.findAll('button').find(b=>b.text()==='清除')!.trigger('click');await flushPromises();expect(get.mock.calls.at(-1)?.[0]).toBe('/reservations?size=20')
})
it('rejects an incomplete date filter before sending another history request',async()=>{
  await open();await wrapper!.findAll('input[type="date"]')[0]!.setValue('2026-10-30');const calls=get.mock.calls.length
  await wrapper!.get('form.history-filters').trigger('submit');await flushPromises();expect(wrapper!.text()).toContain('请选择完整的开始和结束日期');expect(get).toHaveBeenCalledTimes(calls)
})
it('keeps history available when create or query rights are absent',async()=>{
  session.user!.permissions=['seats.reservations.read.own'];get.mockResolvedValueOnce({...caps,canCreateDemoReservation:false}).mockResolvedValueOnce(history());await open();expect(wrapper!.text()).toContain('booking-1');expect(wrapper!.findAll('button').some(b=>b.text()==='再次预约')).toBe(false)
})
it('does not discard an unresolved submission to start another reservation',async()=>{
  savePending({userId:'u',key:'original',intent:record,roomCode:'A101',seatCode:'S01'});await open();expect(wrapper!.text()).toContain('尚未核实的提交');expect(wrapper!.findAll('button').find(b=>b.text()==='再次预约')!.attributes('disabled')).toBeDefined();expect(push).not.toHaveBeenCalled()
})
it('rejects an incomplete list instead of displaying a misleading empty success',async()=>{
  get.mockResolvedValueOnce(caps).mockResolvedValueOnce({});await open();expect(wrapper!.text()).toContain('预约列表响应不完整');expect(wrapper!.text()).not.toContain('当前没有本人的预约记录')
})
it('discards a late history response after the signed-in user changes',async()=>{
  let resolve!:(x:unknown)=>void;get.mockImplementation(async(path:string)=>path==='/reservation-capabilities'?caps:new Promise(r=>{resolve=r}));await open();const oldResolve=resolve
  get.mockImplementation(async(path:string)=>path==='/reservation-capabilities'?caps:history([]));session.user={...session.user!,id:'other'};await flushPromises();oldResolve(history());await flushPromises();expect(wrapper!.text()).not.toContain('booking-1');expect(wrapper!.text()).toContain('当前没有本人的预约记录')
})
it('ordinary capability routes repeat to the normal personal page and accepts ordinary records',async()=>{
  get.mockImplementation(async(path:string)=>path==='/reservation-capabilities'?{...caps,canCreateDemoReservation:false,canCreateReservation:true,stage:'PERSONAL'}:history([{...record,stage:'PERSONAL'}]))
  await open();await wrapper!.findAll('button').find(b=>b.text()==='再次预约')!.trigger('click');expect(push).toHaveBeenCalledWith({path:'/seat-reservations',query:{repeatReservationId:'booking-1'}})
})
it('submits a six digit code and updates only the returned reservation state',async()=>{
  checkIn.mockResolvedValue({...record,status:'CHECKED_IN',version:2,stage:'PERSONAL',checkedInAt:'2026-10-29T01:05:00Z',completedAt:null,serverTime});await open()
  await wrapper!.get('input[autocomplete="one-time-code"]').setValue('012345');await wrapper!.findAll('button').find(b=>b.text()==='签到')!.trigger('submit');await flushPromises()
  expect(checkIn).toHaveBeenCalledWith('booking-1','012345');expect(push).toHaveBeenCalledWith('/seat-usage/booking-1');expect(wrapper!.text()).toContain('座位正在使用中');expect(wrapper!.find('input[autocomplete="one-time-code"]').exists()).toBe(false)
})
it('clears an incorrect code and keeps the form available for another attempt',async()=>{
  checkIn.mockRejectedValue(new ApiError(422,'CHECK_IN_CODE_INVALID','invalid'));await open();const input=wrapper!.get('input[autocomplete="one-time-code"]')
  await input.setValue('654321');await wrapper!.findAll('button').find(b=>b.text()==='签到')!.trigger('submit');await flushPromises()
  expect(wrapper!.text()).toContain('签到码错误，请重新输入');expect((wrapper!.get('input[autocomplete="one-time-code"]').element as HTMLInputElement).value).toBe('');expect(push).not.toHaveBeenCalled()
})
it('explains a no-show cancellation and no longer offers check-in',async()=>{
  get.mockImplementation(async(path:string)=>path==='/reservation-capabilities'?caps:history([{...record,status:'NO_SHOW',version:2,violationReason:'NO_SHOW'}]));await open()
  expect(wrapper!.text()).toContain('系统已取消预约、释放座位，并记录一次未签到违约');expect(wrapper!.find('input[autocomplete="one-time-code"]').exists()).toBe(false)
})
it('uses accessible success and violation treatments and shows persisted usage duration',async()=>{
  const completed={...record,id:'done',status:'COMPLETED',version:3,checkedInAt:'2026-10-29T01:05:00Z',completedAt:'2026-10-29T01:35:06Z',usageDurationSeconds:1806}
  const violation={...record,id:'late',status:'NO_SHOW',version:2,violationReason:'NO_SHOW'}
  get.mockImplementation(async(path:string)=>path==='/reservation-capabilities'?caps:history([completed,violation]));await open()
  expect(wrapper!.text()).toContain('签到成功 · 已完成');expect(wrapper!.text()).toContain('00:30:06');expect(wrapper!.find('.history-success').exists()).toBe(true)
  expect(wrapper!.text()).toContain('未签到违约');expect(wrapper!.find('.history-violation').exists()).toBe(true)
})
