import {mount,flushPromises} from '@vue/test-utils'
import {afterEach,beforeEach,it,expect,vi} from 'vitest'
import ManagementView from '../views/ManagementView.vue'
import {session} from '../session'
import {ApiError} from '../api'
const {request,route}=vi.hoisted(()=>({request:vi.fn(),route:{params:{} as Record<string,string>}}))
vi.mock('vue-router',()=>({useRoute:()=>route}))
vi.mock('../maintenance',async original=>({...await original<object>(),maintenance:request}))
const mounted:ReturnType<typeof mount>[]=[]
function render(){const w=mount(ManagementView,{global:{stubs:{RouterLink:{template:'<a><slot/></a>'}}}});mounted.push(w);return w}
const item={id:'ticket',roomId:'room',faultType:'OTHER',description:'<img src=x>',status:'PENDING_DISPATCH',createdAt:'2026-11-02T00:00:00Z',assigneeId:null}
beforeEach(()=>{route.params={};request.mockReset();session.user={id:'manager',username:'m',displayName:'m',departmentId:'d',active:true,roles:['MEMBER'],permissions:['maintenance.read.scope']}})
afterEach(()=>{mounted.forEach(w=>w.unmount());mounted.length=0})
it('paginates and resets page when filtering with no identity parameters',async()=>{request.mockResolvedValue({items:[item],total:21});const w=render();await flushPromises();expect(w.text()).toContain('未派单');expect(w.find('img').exists()).toBe(false);await w.findAll('button').find(b=>b.text()==='下一页')!.trigger('click');await flushPromises();expect(request).toHaveBeenLastCalledWith('/management/work-orders?page=1&size=20');await w.get('select').setValue('PENDING_DISPATCH');await flushPromises();expect(request).toHaveBeenLastCalledWith('/management/work-orders?page=0&size=20&status=PENDING_DISPATCH')})
it('shows loading, empty and recoverable error',async()=>{let resolve!:(x:unknown)=>void;request.mockImplementationOnce(()=>new Promise(r=>resolve=r));const w=render();expect(w.text()).toContain('正在加载');resolve({items:[],total:0});await flushPromises();expect(w.text()).toContain('暂无符合条件');request.mockRejectedValueOnce(new ApiError(503,'D_UNAVAILABLE','failed'));await w.findAll('button').find(b=>b.text()==='刷新')!.trigger('click');await flushPromises();expect(w.text()).toContain('服务暂时不可用');request.mockResolvedValue({items:[item],total:1});await w.findAll('button').find(b=>b.text()==='重新加载')!.trigger('click');await flushPromises();expect(w.text()).toContain('ticket')})
it('clears detail on forbidden scope and renders no reporter data',async()=>{route.params={id:'ticket'};request.mockResolvedValue(item);const w=render();await flushPromises();expect(request).toHaveBeenCalledWith('/management/work-orders/ticket');expect(w.text()).toContain('房间 ID：room');request.mockRejectedValue(new ApiError(404,'NOT_FOUND','missing'));await w.findAll('button').find(b=>b.text()==='刷新')!.trigger('click');await flushPromises();expect(w.text()).toContain('不在授权范围内');expect(w.text()).not.toContain('房间 ID：room')})
it('does not query without permission even for ADMIN',async()=>{session.user!.roles=['ADMIN'];session.user!.permissions=[];const w=render();await flushPromises();expect(request).not.toHaveBeenCalled();expect(w.text()).toContain('没有管理查询权限')})

it('drops a previous manager response after account switch and revocation',async()=>{
 route.params={id:'ticket'}
 let resolve!:(x:unknown)=>void
 request.mockImplementationOnce(()=>new Promise(r=>{resolve=r}))
 const w=render()
 request.mockRejectedValue(new ApiError(404,'NOT_FOUND','missing'))
 session.user={...session.user!,id:'other-manager',departmentId:'other'};await flushPromises()
 resolve({...item,description:'old department secret'});await flushPromises()
 expect(w.text()).not.toContain('old department secret')
 expect(w.text()).toContain('不在授权范围内')
 session.user!.permissions=[];await flushPromises()
 expect(w.text()).toContain('没有管理查询权限')
})
