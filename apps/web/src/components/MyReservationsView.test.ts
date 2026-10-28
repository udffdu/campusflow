import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import MyReservationsView from '../views/MyReservationsView.vue'
import {session} from '../session'
import {savePending} from '../reservations'
const {get,push}=vi.hoisted(()=>({get:vi.fn(),push:vi.fn()}))
vi.mock('../m1Api',()=>({m1Get:get}))
vi.mock('vue-router',()=>({useRouter:()=>({push})}))
let wrapper:VueWrapper|undefined
const record={id:'booking-1',roomId:'r',seatId:'s',startAt:'2026-10-29T01:00:00Z',endAt:'2026-10-29T02:00:00Z',status:'CONFIRMED',version:1,resourceVersion:1,stage:'PERSONAL_DEMO',productionReady:false}
const caps={canReadOwn:true,canCreateDemoReservation:true,productionReady:false,stage:'PERSONAL_DEMO'}
beforeEach(()=>{sessionStorage.clear();get.mockReset();push.mockReset();session.user={id:'u',username:'student',displayName:'同学',departmentId:'d',active:true,roles:['SEAT_USER'],permissions:['seats.reservations.read.own','seats.availability.read','seats.reservations.create']};get.mockImplementation(async(path:string)=>path==='/reservation-capabilities'?caps:{items:[record],size:20,nextCursor:''})})
afterEach(()=>{wrapper?.unmount();wrapper=undefined})
async function open(){wrapper=mount(MyReservationsView,{global:{stubs:{RouterLink:true}}});await flushPromises()}
it('reads own records without passing a user identity and opens repeat using only the reservation ID',async()=>{
  await open();expect(get).toHaveBeenCalledWith('/reservations?size=20');expect(wrapper!.text()).toContain('booking-1');expect(wrapper!.text()).toContain('已预约')
  await wrapper!.findAll('button').find(b=>b.text()==='再次预约')!.trigger('click');expect(push).toHaveBeenCalledWith({path:'/seat-reservation-demo',query:{repeatReservationId:'booking-1'}})
})
it('shows an empty result separately from failure and retries reads only',async()=>{
  get.mockImplementation(async(path:string)=>{if(path==='/reservation-capabilities')return caps;throw new Error('历史暂不可用')});await open();expect(wrapper!.text()).toContain('历史暂不可用');expect(wrapper!.text()).not.toContain('当前没有本人的预约记录')
  get.mockResolvedValueOnce(caps).mockResolvedValueOnce({items:[],size:20,nextCursor:''});await wrapper!.findAll('button').find(b=>b.text()==='重试')!.trigger('click');await flushPromises();expect(wrapper!.text()).toContain('当前没有本人的预约记录')
})
it('uses the server cursor for next page and returns to the first page on refresh',async()=>{
  get.mockImplementation(async(path:string)=>path==='/reservation-capabilities'?caps:{items:[record],size:20,nextCursor:path.includes('cursor=')?'':'next-token'});await open()
  await wrapper!.findAll('button').find(b=>b.text()==='下一页')!.trigger('click');await flushPromises();expect(get).toHaveBeenCalledWith('/reservations?size=20&cursor=next-token');expect(wrapper!.text()).toContain('第 2 页')
  await wrapper!.findAll('button').find(b=>b.text()==='刷新列表')!.trigger('click');await flushPromises();expect(wrapper!.text()).toContain('第 1 页');expect(get.mock.calls.at(-1)?.[0]).toBe('/reservations?size=20')
})
it('keeps history available when create or query rights are absent',async()=>{
  session.user!.permissions=['seats.reservations.read.own'];get.mockResolvedValueOnce({...caps,canCreateDemoReservation:false}).mockResolvedValueOnce({items:[record],size:20,nextCursor:''});await open();expect(wrapper!.text()).toContain('booking-1');expect(wrapper!.findAll('button').some(b=>b.text()==='再次预约')).toBe(false)
})
it('does not discard an unresolved submission to start another reservation',async()=>{
  savePending({userId:'u',key:'original',intent:record,roomCode:'A101',seatCode:'S01'});await open();expect(wrapper!.text()).toContain('尚未核实的提交');expect(wrapper!.findAll('button').find(b=>b.text()==='再次预约')!.attributes('disabled')).toBeDefined();expect(push).not.toHaveBeenCalled()
})
it('rejects an incomplete list instead of displaying a misleading empty success',async()=>{
  get.mockResolvedValueOnce(caps).mockResolvedValueOnce({});await open();expect(wrapper!.text()).toContain('预约列表响应不完整');expect(wrapper!.text()).not.toContain('当前没有本人的预约记录')
})
it('discards a late history response after the signed-in user changes',async()=>{
  let resolve!:(x:unknown)=>void;get.mockImplementation(async(path:string)=>path==='/reservation-capabilities'?caps:new Promise(r=>{resolve=r}));await open();const oldResolve=resolve
  get.mockImplementation(async(path:string)=>path==='/reservation-capabilities'?caps:{items:[],size:20,nextCursor:''});session.user={...session.user!,id:'other'};await flushPromises();oldResolve({items:[record],size:20,nextCursor:''});await flushPromises();expect(wrapper!.text()).not.toContain('booking-1');expect(wrapper!.text()).toContain('当前没有本人的预约记录')
})
it('ordinary capability routes repeat to the normal personal page and accepts ordinary records',async()=>{
  get.mockImplementation(async(path:string)=>path==='/reservation-capabilities'?{...caps,canCreateDemoReservation:false,canCreateReservation:true,stage:'PERSONAL'}:{items:[{...record,stage:'PERSONAL'}],size:20,nextCursor:''})
  await open();await wrapper!.findAll('button').find(b=>b.text()==='再次预约')!.trigger('click');expect(push).toHaveBeenCalledWith({path:'/seat-reservations',query:{repeatReservationId:'booking-1'}})
})
