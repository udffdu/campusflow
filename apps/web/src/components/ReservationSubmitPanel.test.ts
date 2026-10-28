import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import ReservationSubmitPanel from './ReservationSubmitPanel.vue'
import {session} from '../session'
import {ApiError} from '../api'
import {pendingFor,savePending,type PendingReservation} from '../reservations'
const {submit,get}=vi.hoisted(()=>({submit:vi.fn(),get:vi.fn()}))
vi.mock('../reservations',async original=>({...await original<typeof import('../reservations')>(),submitReservation:submit}))
vi.mock('../m1Api',()=>({m1Get:get}))
let wrapper:VueWrapper|undefined
const selection={roomId:'room',seatId:'seat',startAt:'2026-10-29T01:00:00Z',endAt:'2026-10-29T02:00:00Z',roomCode:'A101',seatCode:'S1'}
const record={...selection,id:'booking',status:'CONFIRMED',version:1,resourceVersion:1,stage:'PERSONAL_DEMO',productionReady:false}
beforeEach(()=>{sessionStorage.clear();submit.mockReset();get.mockReset();session.user={id:'u',username:'student',displayName:'同学',departmentId:'d',active:true,roles:['SEAT_USER'],permissions:['seats.reservations.create']}})
afterEach(()=>{wrapper?.unmount();wrapper=undefined})
async function open(){wrapper=mount(ReservationSubmitPanel,{props:{selection,canCreate:true}});await flushPromises()}
it('persists intent before sending and preserves the same key after unknown response',async()=>{
  submit.mockImplementationOnce(async(p:PendingReservation)=>{expect(pendingFor('u')?.key).toBe(p.key);throw new ApiError(0,'RESULT_UNKNOWN','结果未知')}).mockResolvedValueOnce(record)
  await open();await wrapper!.get('button').trigger('click');await flushPromises();const key=submit.mock.calls[0]![0].key
  expect(wrapper!.text()).toContain('提交结果尚未核实');expect(pendingFor('u')?.key).toBe(key)
  await wrapper!.findAll('button').find(b=>b.text()==='使用原请求重试')!.trigger('click');await flushPromises()
  expect(submit.mock.calls[1]![0].key).toBe(key);expect(pendingFor('u')).toBeUndefined();expect(wrapper!.text()).toContain('演示预约已保存')
})
it('restores a pending submission on remount without sending a second POST',async()=>{
  savePending({userId:'u',key:'original',intent:selection,roomCode:'A101',seatCode:'S1'});get.mockResolvedValueOnce(record);await open()
  expect(get).toHaveBeenCalledWith('/reservation-requests/original');expect(submit).not.toHaveBeenCalled();expect(wrapper!.text()).toContain('演示预约已保存')
})
it('does not treat recovery 404 as proof that the first request failed',async()=>{
  savePending({userId:'u',key:'inflight',intent:selection,roomCode:'A101',seatCode:'S1'});get.mockRejectedValueOnce(new ApiError(404,'RESERVATION_NOT_FOUND','可能仍在处理中'));await open()
  expect(pendingFor('u')?.key).toBe('inflight');expect(wrapper!.text()).toContain('使用原请求重试')
})
it('retains a successful POST when the subsequent read is forbidden',async()=>{
  submit.mockResolvedValueOnce(record);get.mockRejectedValueOnce(new ApiError(403,'FORBIDDEN','缺少读取权限'));await open();await wrapper!.get('button').trigger('click');await flushPromises()
  await wrapper!.findAll('button').find(b=>b.text()==='刷新本人记录')!.trigger('click');await flushPromises()
  expect(wrapper!.text()).toContain('演示预约已保存');expect(wrapper!.get('[role=alert]').text()).toContain('记录已保存');expect(submit).toHaveBeenCalledTimes(1)
})
it('allows a new choice after a definite transactional conflict',async()=>{
  submit.mockRejectedValueOnce(new ApiError(409,'SEAT_TIME_CONFLICT','座位已被占用'));await open();await wrapper!.get('button').trigger('click');await flushPromises()
  expect(pendingFor('u')).toBeUndefined();expect(wrapper!.emitted('pending')?.at(-1)).toEqual([false]);expect(wrapper!.get('[role=alert]').text()).toContain('已被占用')
})
it('keeps pending requests isolated by user and hides submit without create capability',async()=>{
  savePending({userId:'other',key:'foreign',intent:selection,roomCode:'A101',seatCode:'S1'});wrapper=mount(ReservationSubmitPanel,{props:{selection,canCreate:false}});await flushPromises()
  expect(get).not.toHaveBeenCalled();expect(wrapper.findAll('button').some(b=>b.text()==='提交演示预约')).toBe(false)
})

it('retains the original request when recovery returns an incomplete response',async()=>{
  savePending({userId:'u',key:'incomplete',intent:selection,roomCode:'A101',seatCode:'S1'});get.mockResolvedValueOnce({});await open()
  expect(pendingFor('u')?.key).toBe('incomplete');expect(wrapper!.text()).not.toContain('演示预约已保存');expect(wrapper!.text()).toContain('响应不完整')
})
