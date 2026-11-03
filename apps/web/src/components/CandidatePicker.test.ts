import {mount,flushPromises} from '@vue/test-utils'
import {it,expect,vi,afterEach} from 'vitest'
import CandidatePicker from './CandidatePicker.vue'
import {session} from '../session'
import {ApiError} from '../api'
const {request}=vi.hoisted(()=>({request:vi.fn()}))
vi.mock('../maintenance',async original=>({...await original<object>(),maintenance:request}))
const wrappers:ReturnType<typeof mount>[]=[]
function render(permission=true){session.user={id:'m',username:'m',displayName:'M',departmentId:'d',active:true,roles:[],permissions:permission?['maintenance.dispatch']:[]};const w=mount(CandidatePicker,{props:{orderId:'order'}});wrappers.push(w);return w}
afterEach(()=>{wrappers.forEach(w=>w.unmount());wrappers.length=0;request.mockReset()})
it('shows candidates, selects, paginates and clears stale selection',async()=>{request.mockResolvedValue({items:[{id:'r',displayName:'Repairer',active:true,qualification:'maintenance.repair',globalScope:false,departmentIds:['d']}],total:21});const w=render();await flushPromises();await w.get('input[type=radio]').setValue();expect(w.text()).toContain('已选择：Repairer');expect(w.emitted('select')!.at(-1)).toEqual([expect.objectContaining({id:'r'})]);await w.findAll('button').find(b=>b.text()==='下一页')!.trigger('click');await flushPromises();expect(request).toHaveBeenLastCalledWith('/management/work-orders/order/candidates?page=1&size=20&q=');expect(w.text()).not.toContain('已选择：');await w.get('input:not([type=radio])').setValue('李');await w.get('form').trigger('submit');await flushPromises();expect(request).toHaveBeenLastCalledWith('/management/work-orders/order/candidates?page=0&size=20&q=%E6%9D%8E')})
it('shows recoverable dependency error and true empty state',async()=>{request.mockRejectedValueOnce(new ApiError(503,'D_CANDIDATES_NOT_READY','pending'));const w=render();await flushPromises();expect(w.text()).toContain('维修人员服务尚未接入');expect(w.find('input[type=radio]').exists()).toBe(false);request.mockResolvedValue({items:[],total:0});await w.findAll('button').find(b=>b.text()==='重新加载')!.trigger('click');await flushPromises();expect(w.text()).toContain('没有符合条件');expect(w.emitted('select')!.every(e=>e[0]===null)).toBe(true)})
it('does not query or select without dispatch permission',async()=>{const w=render(false);await flushPromises();expect(request).not.toHaveBeenCalled();expect(w.text()).toContain('没有选择维修人员的权限')})

it('does not allow inactive or unqualified candidates to be selected',async()=>{
 request.mockResolvedValue({items:[
 {id:'off',displayName:'Disabled',active:false,qualification:'maintenance.repair',globalScope:true,departmentIds:[]},
 {id:'bad',displayName:'Unqualified',active:true,qualification:'spaces.write',globalScope:true,departmentIds:[]}
 ],total:2})
 const w=render();await flushPromises()
 for(const radio of w.findAll('input[type=radio]'))expect(radio.attributes('disabled')).toBeDefined()
 expect(w.emitted('select')!.every(e=>e[0]===null)).toBe(true)
})
it('drops stale candidates after account switch and clears selection on revocation',async()=>{
 let resolve!:(x:unknown)=>void
 request.mockImplementationOnce(()=>new Promise(r=>{resolve=r}))
 const w=render()
 request.mockResolvedValue({items:[],total:0})
 session.user={...session.user!,id:'new-manager'};await flushPromises()
 resolve({items:[{id:'old',displayName:'Old private candidate',active:true,qualification:'maintenance.repair',globalScope:true,departmentIds:[]}],total:1});await flushPromises()
 expect(w.text()).not.toContain('Old private candidate')
 expect(w.find('input[type=radio]').exists()).toBe(false)
 session.user!.permissions=[];await flushPromises()
 expect(w.text()).toContain('没有选择维修人员的权限')
 expect(w.emitted('select')!.at(-1)).toEqual([null])
})
