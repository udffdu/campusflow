import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import SeatQueryView from '../views/SeatQueryView.vue'
const {get}=vi.hoisted(()=>({get:vi.fn()}))
vi.mock('../m1Api',async original=>({...await original<typeof import('../m1Api')>(),m1Get:get}))
let wrapper:VueWrapper|undefined
const room={id:'r',code:'A101',name:'测试教室',bookingScope:'CAMPUS',openingHours:[]}
const data={roomId:'r',reason:'NONE',items:[{id:'s',code:'S01',hasPowerOutlet:true}],page:0,size:20,total:1,stage:'CANDIDATE_ONLY',canReserve:false}
beforeEach(()=>{get.mockReset();get.mockImplementation(async(path:string)=>path.startsWith('/rooms')?{items:[room],page:0,size:20,total:1}:data)})
afterEach(()=>wrapper?.unmount())
async function open(){wrapper=mount(SeatQueryView);await flushPromises()}
it('queries with explicit Shanghai times and power, and offers no reservation write',async()=>{
  await open();const inputs=wrapper!.findAll('input');await inputs[0]!.setValue('2026-10-29T09:00');await inputs[1]!.setValue('2026-10-29T11:00');await inputs[2]!.setValue(true)
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
