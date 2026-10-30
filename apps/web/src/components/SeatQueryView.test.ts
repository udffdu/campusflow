import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import SeatQueryView from '../views/SeatQueryView.vue'
import {session} from '../session'
import {savePending} from '../reservations'
const {get,route,replace}=vi.hoisted(()=>({get:vi.fn(),route:{query:{} as Record<string,string>},replace:vi.fn()}))
vi.mock('vue-router',()=>({useRoute:()=>route,useRouter:()=>({replace})}))
vi.mock('../m1Api',async original=>({...await original<typeof import('../m1Api')>(),m1Get:get}))
let wrapper:VueWrapper|undefined
const room={id:'r',code:'A101',name:'测试教室',bookingScope:'CAMPUS',openingHours:[]}
const data={roomId:'r',reason:'NONE',items:[{id:'s',code:'S01',hasPowerOutlet:true}],page:0,size:20,total:1,stage:'CANDIDATE_ONLY',canReserve:false}
beforeEach(()=>{sessionStorage.clear();route.query={};replace.mockReset();session.user={id:'u',username:'student',displayName:'同学',email:'student@example.invalid',departmentId:'d',active:true,roles:['SEAT_USER'],permissions:['seats.availability.read','seats.reservations.create','seats.reservations.read.own']};get.mockReset();get.mockImplementation(async(path:string)=>path.startsWith('/rooms')?{items:[room],page:0,size:20,total:1}:data)})
afterEach(()=>wrapper?.unmount())
async function open(){wrapper=mount(SeatQueryView);await flushPromises()}
async function fillTimes(start:string,end:string){
  await wrapper!.get('input[name="start-date"]').setValue(start.slice(0,10));await wrapper!.get('select[name="start-hour"]').setValue(start.slice(11,13))
  await wrapper!.get('input[name="end-date"]').setValue(end.slice(0,10));await wrapper!.get('select[name="end-hour"]').setValue(end.slice(11,13))
}
it('queries with explicit Shanghai times and power, and offers no reservation write',async()=>{
  await open();await fillTimes('2026-10-29T09:00','2026-10-29T11:00');await wrapper!.get('input[type=checkbox]').setValue(true)
  expect(wrapper!.find('input[type=datetime-local]').exists()).toBe(false)
  await wrapper!.get('form').trigger('submit');await flushPromises()
  const url=get.mock.calls[1]![0] as string;const p=new URL(url,'http://test').searchParams
  expect(p.get('startAt')).toBe('2026-10-29T01:00:00Z');expect(p.get('endAt')).toBe('2026-10-29T03:00:00Z');expect(p.get('requiresPower')).toBe('true');expect(p.has('userId')).toBe(false)
  expect(wrapper!.text()).toContain('S01');expect(wrapper!.text()).toContain('有插座');expect(wrapper!.text()).toContain('预约暂未开放')
  expect(wrapper!.findAll('button').some(b=>b.text().includes('提交预约'))).toBe(false)
})
it('clears stale results on changed filters',async()=>{await open();await wrapper!.get('form').trigger('submit');await flushPromises();expect(wrapper!.text()).toContain('S01');await wrapper!.get('input[type=checkbox]').setValue(true);expect(wrapper!.text()).not.toContain('S01')})
it('keeps service failures distinct from an empty candidate list and permits retry',async()=>{
  await open();get.mockRejectedValueOnce(new Error('服务暂不可用'));await wrapper!.get('form').trigger('submit');await flushPromises();expect(wrapper!.get('[role=alert]').text()).toContain('服务暂不可用');expect(wrapper!.text()).not.toContain('当前条件下没有候选')
  get.mockResolvedValueOnce({...data,items:[],total:0});await wrapper!.get('form').trigger('submit');await flushPromises();expect(wrapper!.text()).toContain('当前条件下没有候选座位');expect(wrapper!.find('[role=alert]').exists()).toBe(false)
})
it('does not offer query form when no authorized rooms exist',async()=>{get.mockResolvedValueOnce({items:[],page:0,size:20,total:0});await open();expect(wrapper!.find('form').exists()).toBe(false);expect(wrapper!.text()).toContain('没有已启用且向你开放的教室')})
it('ignores an older in-flight response after a changed condition',async()=>{
  await open();let resolve!:(x:unknown)=>void;get.mockImplementationOnce(()=>new Promise(r=>{resolve=r}));await wrapper!.get('form').trigger('submit');await wrapper!.get('input[type=checkbox]').setValue(true);resolve(data);await flushPromises();expect(wrapper!.text()).not.toContain('S01')
})

it('room pagination only loads rooms and does not submit the query form',async()=>{
  get.mockResolvedValueOnce({items:[room],page:0,size:20,total:21});await open()
  get.mockResolvedValueOnce({items:[{...room,id:'r2',code:'B201'}],page:1,size:20,total:21})
  await wrapper!.findAll('button').find(b=>b.text()==='下一页')!.trigger('click');await flushPromises()
  expect(get.mock.calls.map(c=>c[0])).toEqual(['/rooms?page=0&size=20','/rooms?page=1&size=20'])
  expect((wrapper!.get('select').element as HTMLSelectElement).value).toBe('r2')
})
it('room-list failures offer retry and never show a successful empty state',async()=>{
  get.mockRejectedValueOnce(new Error('教室服务暂不可用'));await open();expect(wrapper!.get('[role=alert]').text()).toContain('暂不可用');expect(wrapper!.text()).not.toContain('当前没有已启用')
  await wrapper!.findAll('button').find(b=>b.text()==='重试')!.trigger('click');await flushPromises();expect(wrapper!.get('select').text()).toContain('A101')
})

async function repeatOpen(){
  route.query={repeatReservationId:'old-booking'}
  get.mockImplementation(async(path:string)=>path.endsWith('/repeat-context')?{reservationId:'old-booking',room,seat:{id:'s',code:'S01'},requiresNewTime:true,canReserve:false}:path==='/reservation-capabilities'?{canCreateDemoReservation:true,canReadOwn:true,productionReady:false,stage:'PERSONAL_DEMO'}:data)
  wrapper=mount(SeatQueryView,{props:{demo:true}});await flushPromises()
}
it('loads own repeat context and requires new times without submitting or copying old values',async()=>{
  const fetch=vi.spyOn(globalThis,'fetch');await repeatOpen()
  expect(get).toHaveBeenCalledWith('/reservations/old-booking/repeat-context');expect(wrapper!.text()).toContain('再次预约 · A101 · S01')
  expect(wrapper!.findAll('input[type=date]').map(x=>(x.element as HTMLInputElement).value)).toEqual(['','']);expect(wrapper!.findAll('select[name$=hour]').map(x=>(x.element as HTMLSelectElement).value)).toEqual(['',''])
  expect(wrapper!.findAll('button').some(b=>b.text()==='提交演示预约')).toBe(false);expect(fetch).not.toHaveBeenCalled();fetch.mockRestore()
})
it('requeries the reused seat before displaying a confirmation for the newly chosen time',async()=>{
  await repeatOpen();await fillTimes('2026-10-29T09:00','2026-10-29T10:00')
  await wrapper!.get('form').trigger('submit');await flushPromises();await wrapper!.findAll('button').find(b=>b.text()==='选择此座位')!.trigger('click');await flushPromises()
  expect(get.mock.calls.filter(c=>c[0].startsWith('/seat-candidates'))).toHaveLength(2);expect(wrapper!.text()).toContain('确认演示预约');expect(wrapper!.text()).toContain('提交演示预约')
})
it('does not substitute another seat when the reused seat is not a candidate',async()=>{
  await repeatOpen();await fillTimes('2026-10-29T09:00','2026-10-29T10:00')
  get.mockResolvedValueOnce({...data,items:[{id:'different',code:'S02',hasPowerOutlet:true}]});await wrapper!.get('form').trigger('submit');await flushPromises()
  expect(wrapper!.text()).toContain('此页没有原座位');expect(wrapper!.findAll('button').some(b=>b.text()==='选择此座位')).toBe(false)
})
it('blocks repeat context while a previous submission is still unresolved',async()=>{
  savePending({userId:'u',key:'original-key',intent:{roomId:'r',seatId:'s',startAt:'2026-10-29T01:00:00Z',endAt:'2026-10-29T02:00:00Z'},roomCode:'A101',seatCode:'S01'})
  await repeatOpen();expect(get.mock.calls.some(c=>c[0].endsWith('/repeat-context'))).toBe(false);expect(wrapper!.text()).toContain('原提交结果')
})
it('shows a repeat resource failure without a selectable form',async()=>{
  route.query={repeatReservationId:'old-booking'};get.mockRejectedValue(new Error('原教室或座位已停用'))
  wrapper=mount(SeatQueryView,{props:{demo:true}});await flushPromises();expect(wrapper.text()).toContain('原教室或座位已停用');expect(wrapper.find('form').exists()).toBe(false)
})
it('ordinary booking uses its own capability and displays the configured calendar window',async()=>{
  get.mockImplementation(async(path:string)=>path==='/reservation-capabilities'?{canCreateReservation:true,canCreateDemoReservation:false,advanceMode:'CALENDAR_DAYS',advanceDays:1,durationHours:4}:path.startsWith('/rooms')?{items:[room],page:0,size:20,total:1}:data)
  wrapper=mount(SeatQueryView,{props:{booking:true}});await flushPromises();await fillTimes('2026-10-29T09:00','2026-10-29T10:00');await wrapper.get('form').trigger('submit');await flushPromises()
  expect(wrapper.text()).toContain('可预约今天和明天');expect(wrapper.text()).toContain('整室申请和维修限制待接入');await wrapper.findAll('button').find(b=>b.text()==='选择此座位')!.trigger('click');await flushPromises();expect(wrapper.text()).toContain('确认预约');expect(wrapper.text()).not.toContain('提交演示预约')
})
it('legacy demo capability cannot open the ordinary page submit control',async()=>{
  get.mockImplementation(async(path:string)=>path==='/reservation-capabilities'?{canCreateReservation:false,canCreateDemoReservation:true}:path.startsWith('/rooms')?{items:[room],page:0,size:20,total:1}:data)
  wrapper=mount(SeatQueryView,{props:{booking:true}});await flushPromises();await wrapper.get('form').trigger('submit');await flushPromises();expect(wrapper.findAll('button').some(b=>b.text()==='选择此座位')).toBe(false);expect(wrapper.text()).toContain('未开放当前预约入口')
})
