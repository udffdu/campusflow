import {flushPromises,mount} from '@vue/test-utils'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import ReportView from '../views/ReportView.vue'
import MyReportsView from '../views/MyReportsView.vue'
import {session} from '../session'
import {ApiError} from '../api'
const {request,route}=vi.hoisted(()=>({request:vi.fn(),route:{params:{} as Record<string,string>}}))
vi.mock('vue-router',()=>({useRoute:()=>route}))
vi.mock('../maintenance',async importOriginal=>({...await importOriginal<object>(),maintenance:request,maintenanceEnabled:()=>true}))
const wrappers:ReturnType<typeof mount>[]=[]
function render(component:any){const w=mount(component,{global:{stubs:{RouterLink:{template:'<a><slot/></a>'}}}});wrappers.push(w);return w}
beforeEach(()=>{request.mockReset();route.params={};session.user={id:'a',username:'a',displayName:'A',active:true,departmentId:'d',roles:['MEMBER'],permissions:['maintenance.report','maintenance.read.own']}})
afterEach(()=>{wrappers.forEach(w=>w.unmount());wrappers.length=0})
async function fill(w:ReturnType<typeof mount>){await w.get('input').setValue('00000000-0000-0000-0000-000000000001');await w.get('textarea').setValue('broken lamp')}
it('locks clicks, retries same key, rotates after success',async()=>{let reject!:(e:Error)=>void;request.mockImplementationOnce(()=>new Promise((_,r)=>{reject=r}));const w=render(ReportView);await fill(w);await w.get('form').trigger('submit');await w.get('form').trigger('submit');expect(request).toHaveBeenCalledTimes(1);const key=request.mock.calls[0]![2];reject(new ApiError(0,'NETWORK','failed'));await flushPromises();expect(w.text()).toContain('原请求重试');expect(w.get('fieldset').attributes('disabled')).toBeDefined();request.mockResolvedValue({id:'created'});await w.get('form').trigger('submit');await flushPromises();expect(request.mock.calls[1]![2]).toBe(key);expect(w.text()).toContain('报修已提交');await w.findAll('button').find(b=>b.text()==='新建报修')!.trigger('click');await fill(w);await w.get('form').trigger('submit');await flushPromises();expect(request.mock.calls[2]![2]).not.toBe(key)})
it('validates and blocks missing permission',async()=>{const w=render(ReportView);await w.get('form').trigger('submit');expect(w.text()).toContain('5–1000');expect(request).not.toHaveBeenCalled();session.user!.permissions=[];await flushPromises();expect(w.text()).toContain('没有报修权限')})
it('allows correction after definitive 400',async()=>{request.mockRejectedValue(new ApiError(400,'INVALID_ROOM','invalid'));const w=render(ReportView);await fill(w);await w.get('form').trigger('submit');await flushPromises();expect(w.text()).toContain('房间不可报修');expect(w.get('fieldset').attributes('disabled')).toBeUndefined()})
it('shows loading and empty state, paginates and escapes text',async()=>{let resolve!:(x:unknown)=>void;request.mockImplementationOnce(()=>new Promise(r=>{resolve=r}));const w=render(MyReportsView);expect(w.text()).toContain('正在加载');resolve({items:[],total:0});await flushPromises();expect(w.text()).toContain('暂无报修记录');request.mockResolvedValue({items:[{id:'own',status:'PENDING_DISPATCH',faultType:'OTHER',description:'<img src=x>',createdAt:'2026-11-02T00:00:00Z'}],total:21});await w.findAll('button').find(b=>b.text()==='刷新')!.trigger('click');await flushPromises();expect(w.text()).toContain('<img src=x>');expect(w.find('img').exists()).toBe(false);await w.findAll('button').find(b=>b.text()==='下一页')!.trigger('click');await flushPromises();expect(request).toHaveBeenLastCalledWith('/work-orders?page=1&size=20')})
it('loads detail and clears stale content on 404',async()=>{route.params={id:'own'};request.mockResolvedValue({id:'own',roomId:'room',status:'PENDING_DISPATCH',faultType:'OTHER',description:'broken lamp',createdAt:'2026-11-02T00:00:00Z',history:[]});const w=render(MyReportsView);await flushPromises();expect(request).toHaveBeenCalledWith('/work-orders/own');expect(w.text()).toContain('broken lamp');request.mockRejectedValue(new ApiError(404,'NOT_FOUND','missing'));await w.findAll('button').find(b=>b.text()==='刷新')!.trigger('click');await flushPromises();expect(w.text()).not.toContain('broken lamp');expect(w.text()).toContain('不属于本人')})
it('does not query without permission',async()=>{session.user!.permissions=[];const w=render(MyReportsView);await flushPromises();expect(request).not.toHaveBeenCalled();expect(w.text()).toContain('没有本人查询权限')})

it('discards an old account submission result and resets retry context',async()=>{
 let resolve!:(x:unknown)=>void
 request.mockImplementationOnce(()=>new Promise(r=>{resolve=r}))
 const w=render(ReportView);await fill(w);await w.get('form').trigger('submit')
 const oldKey=request.mock.calls[0]![2]
 session.user={...session.user!,id:'b'};await flushPromises()
 expect((w.get('textarea').element as HTMLTextAreaElement).value).toBe('')
 resolve({id:'account-a-private-order'});await flushPromises()
 expect(w.text()).not.toContain('account-a-private-order')
 expect(w.text()).not.toContain('报修已提交')
 request.mockResolvedValue({id:'account-b-order'})
 await fill(w);await w.get('form').trigger('submit');await flushPromises()
 expect(request.mock.calls[1]![2]).not.toBe(oldKey)
 expect(w.text()).toContain('account-b-order')
})
it('clears detail on account switch and ignores the old response',async()=>{
 route.params={id:'private'}
 let resolve!:(x:unknown)=>void
 request.mockImplementationOnce(()=>new Promise(r=>{resolve=r}))
 const w=render(MyReportsView)
 request.mockRejectedValue(new ApiError(404,'NOT_FOUND','missing'))
 session.user={...session.user!,id:'b'};await flushPromises()
 resolve({id:'private',description:'account A secret',createdAt:'2026-11-02T00:00:00Z'});await flushPromises()
 expect(w.text()).not.toContain('account A secret')
 expect(w.text()).toContain('不属于本人')
 session.user!.permissions=[];await flushPromises()
 expect(w.text()).toContain('没有本人查询权限')
})

it('renders authorized detail history and clears it on session expiry',async()=>{
 route.params={id:'ticket'}
 request.mockResolvedValue({id:'ticket',roomId:'room',status:'ASSIGNED',faultType:'OTHER',description:'broken lamp',createdAt:'2026-11-03T00:00:00Z',history:[{id:'history',action:'CREATED',actorId:'actual-reporter-id',fromStatus:null,toStatus:'PENDING_DISPATCH',assigneeId:null,remark:null,occurredAt:'2026-11-03T00:00:00Z'}]})
 const w=render(MyReportsView)
 await flushPromises();expect(w.text()).toContain('actual-reporter-id');expect(w.text()).toContain('创建工单');expect(w.text()).toContain('08:00:00')
 request.mockRejectedValue(new ApiError(401,'UNAUTHENTICATED','expired'))
 await w.findAll('button').find(b=>b.text()==='刷新')!.trigger('click');await flushPromises()
 expect(w.text()).not.toContain('actual-reporter-id');expect(w.text()).toContain('登录已失效')
 session.user!.permissions=[];await flushPromises();expect(w.find('[aria-label="工单操作历史"]').exists()).toBe(false)
})
