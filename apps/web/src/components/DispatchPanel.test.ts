import {mount,flushPromises} from '@vue/test-utils'
import {it,expect,vi,afterEach} from 'vitest'
import DispatchPanel from './DispatchPanel.vue'
import {session} from '../session'
import {ApiError} from '../api'
const {request}=vi.hoisted(()=>({request:vi.fn()}))
vi.mock('../maintenance',async o=>({...await o<object>(),maintenance:request}))
const wrappers:ReturnType<typeof mount>[]=[]
function render(){session.user={id:'m',username:'m',displayName:'m',active:true,departmentId:'d',roles:[],permissions:['maintenance.dispatch']};const w=mount(DispatchPanel,{props:{orderId:'one',version:1},global:{stubs:{CandidatePicker:{template:'<button @click="$emit(\'select\',{id: \'repairer\'})">选择</button>'}}}});wrappers.push(w);return w}
afterEach(()=>{wrappers.forEach(w=>w.unmount());wrappers.length=0;request.mockReset()})
it('locks clicks and retries the original request then refreshes',async()=>{let reject!:(e:Error)=>void;request.mockImplementationOnce(()=>new Promise((_,r)=>reject=r));const w=render();await w.findAll('button')[0]!.trigger('click');await w.findAll('button')[1]!.trigger('click');await w.findAll('button')[1]!.trigger('click');expect(request).toHaveBeenCalledOnce();const key=request.mock.calls[0]![2];reject(new ApiError(0,'NETWORK','failed'));await flushPromises();request.mockResolvedValue({status:'ASSIGNED'});await w.findAll('button')[1]!.trigger('click');await flushPromises();expect(request.mock.calls[1]![2]).toBe(key);expect(request.mock.calls[1]![1]).toEqual({assigneeId:'repairer',expectedVersion:1});expect(w.emitted('done')).toHaveLength(1)})
it('stops on conflict and requires refresh',async()=>{request.mockRejectedValue(new ApiError(409,'ASSIGNEE_INELIGIBLE','changed'));const w=render();await w.findAll('button')[0]!.trigger('click');await w.findAll('button')[1]!.trigger('click');await flushPromises();expect(w.text()).toContain('人员资格已变化');expect(w.findAll('button')[1]!.attributes('disabled')).toBeDefined()})

it('ignores old completion after account switch and clears the retry context',async()=>{
 let resolve!:(x:unknown)=>void
 request.mockImplementationOnce(()=>new Promise(r=>{resolve=r}))
 const w=render();await w.findAll('button')[0]!.trigger('click');await w.findAll('button')[1]!.trigger('click')
 session.user={...session.user!,id:'new-user'};await flushPromises()
 resolve({status:'ASSIGNED'});await flushPromises()
 expect(w.emitted('done')).toBeUndefined()
 expect(w.findAll('button')[1]!.attributes('disabled')).toBeDefined()
 session.user!.permissions=[];await flushPromises()
 expect(w.findAll('button')[1]!.attributes('disabled')).toBeDefined()
})
