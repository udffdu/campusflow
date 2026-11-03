import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import SeatUsageView from '../views/SeatUsageView.vue'
const {read,leave}=vi.hoisted(()=>({read:vi.fn(),leave:vi.fn()}))
vi.mock('../reservations',()=>({usageSession:read,leaveSeat:leave}))
vi.mock('vue-router',()=>({useRoute:()=>({params:{reservationId:'booking-1'}})}))
const active={id:'booking-1',roomId:'room',seatId:'seat',startAt:'2026-10-30T01:00:00Z',endAt:'2026-10-30T03:00:00Z',status:'CHECKED_IN',version:2,resourceVersion:1,stage:'PERSONAL',productionReady:false,checkedInAt:'2026-10-30T01:00:00Z',completedAt:null,serverTime:'2026-10-30T01:10:00Z'}
let wrapper:VueWrapper|undefined
beforeEach(()=>{read.mockReset().mockResolvedValue(active);leave.mockReset().mockResolvedValue({...active,status:'COMPLETED',version:3,completedAt:'2026-10-30T01:20:00Z',serverTime:'2026-10-30T01:20:00Z'});vi.useFakeTimers();vi.setSystemTime(new Date('2026-10-30T01:10:00Z'))})
afterEach(()=>{wrapper?.unmount();wrapper=undefined;vi.useRealTimers()})
it('renders the persisted elapsed time and requires a second confirmation before leaving',async()=>{
  wrapper=mount(SeatUsageView,{global:{stubs:{RouterLink:true}}});await flushPromises()
  expect(read).toHaveBeenCalledWith('booking-1');expect(wrapper.text()).toContain('00:10:00');expect(wrapper.text()).toContain('座位使用中')
  await wrapper.findAll('button').find(button=>button.text()==='结束使用并退座')!.trigger('click');expect(leave).not.toHaveBeenCalled();expect(wrapper.text()).toContain('确认现在结束使用')
  await wrapper.findAll('button').find(button=>button.text()==='确认退座')!.trigger('click');await flushPromises()
  expect(leave).toHaveBeenCalledWith('booking-1');expect(wrapper.text()).toContain('退座成功');expect(wrapper.text()).toContain('00:20:00')
})
