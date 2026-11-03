import {mount,flushPromises} from '@vue/test-utils'
import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import RepairActions from './RepairActions.vue'
import {session} from '../session'
import {ApiError} from '../api'
const {request}=vi.hoisted(()=>({request:vi.fn()}))
vi.mock('../maintenance',async original=>({...await original<object>(),maintenance:request}))
const wrappers:ReturnType<typeof mount>[]=[]
const order={id:'order',status:'ASSIGNED',version:2,assigneeId:'worker'}
function render(mode:'repair'|'accept'='repair',value=order){const w=mount(RepairActions,{props:{order:value,mode}});wrappers.push(w);return w}
beforeEach(()=>{request.mockReset();session.user={id:'worker',username:'w',displayName:'worker',departmentId:'d',active:true,roles:['MEMBER'],permissions:['maintenance.repair']}})
afterEach(()=>{wrappers.forEach(w=>w.unmount());wrappers.length=0})
it('starts with version and preserves the original intent after network failure',async()=>{
 request.mockRejectedValueOnce(new ApiError(0,'NETWORK','lost')).mockResolvedValueOnce({})
 const w=render();await w.get('button').trigger('click');await flushPromises()
 expect(request.mock.calls[0]![0]).toBe('/repair/work-orders/order/start')
 expect(request.mock.calls[0]![1]).toEqual({expectedVersion:2,remark:''})
 expect(w.get('textarea').attributes('disabled')).toBeDefined()
 await w.get('button').trigger('click');await flushPromises()
 expect(request.mock.calls[1]).toEqual(request.mock.calls[0]);expect(w.emitted('done')).toHaveLength(1)
})
it('requires a completion explanation and sends no actor or target status',async()=>{
 request.mockResolvedValue({});const w=render('repair',{...order,status:'IN_PROGRESS',version:3})
 await w.get('button').trigger('click');expect(request).not.toHaveBeenCalled()
 await w.get('textarea').setValue(' replaced the lamp ');await w.get('button').trigger('click');await flushPromises()
 expect(request.mock.calls[0]![1]).toEqual({expectedVersion:3,remark:'replaced the lamp'})
})
it('requires a rejection reason but allows pass without a note',async()=>{
 session.user={...session.user!,id:'reporter',permissions:['maintenance.accept']};request.mockResolvedValue({})
 const w=render('accept',{...order,status:'PENDING_ACCEPTANCE',version:4})
 await w.findAll('button')[1]!.trigger('click');expect(request).not.toHaveBeenCalled()
 await w.findAll('button')[0]!.trigger('click');await flushPromises();expect(request.mock.calls[0]![0]).toBe('/work-orders/order/acceptance/pass')
})
it('hides repair for others and self acceptance even with permission',()=>{
 session.user={...session.user!,id:'other'};expect(render().find('button').exists()).toBe(false)
 session.user={...session.user!,id:'worker',permissions:['maintenance.accept']};expect(render('accept',{...order,status:'PENDING_ACCEPTANCE'}).find('button').exists()).toBe(false)
})
it('requires refresh after a conflict and does not issue a new request',async()=>{
 request.mockRejectedValue(new ApiError(409,'CONFLICT','stale'));const w=render();await w.get('button').trigger('click');await flushPromises()
 expect(w.text()).toContain('请刷新后确认');expect(w.findAll('button')[0]!.attributes('disabled')).toBeDefined()
 await w.findAll('button')[1]!.trigger('click');expect(w.emitted('done')).toHaveLength(1);expect(request).toHaveBeenCalledTimes(1)
})
it('ignores old responses and clears drafts after account switch',async()=>{
 let resolve!:(value:unknown)=>void;request.mockImplementation(()=>new Promise(r=>resolve=r))
 const w=render();await w.get('button').trigger('click');session.user={...session.user!,id:'other'};await flushPromises();resolve({});await flushPromises()
 expect(w.emitted('done')).toBeUndefined();expect(w.find('textarea').exists()).toBe(false)
})
it('uses the current version after refreshed props',async()=>{
 request.mockRejectedValueOnce(new ApiError(409,'CONFLICT','stale')).mockResolvedValueOnce({});const w=render()
 await w.get('button').trigger('click');await flushPromises();const previous=request.mock.calls[0]![2]
 await w.setProps({order:{...order,version:3}});await w.get('button').trigger('click');await flushPromises()
 expect(request.mock.calls[1]![1]).toEqual({expectedVersion:3,remark:''});expect(request.mock.calls[1]![2]).not.toEqual(previous)
})
