import {mount,flushPromises} from '@vue/test-utils'
import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import MyRepairsView from '../views/MyRepairsView.vue'
import {session} from '../session'
const {request,route}=vi.hoisted(()=>({request:vi.fn(),route:{params:{} as Record<string,string>}}))
vi.mock('vue-router',()=>({useRoute:()=>route}))
vi.mock('../maintenance',async original=>({...await original<object>(),maintenance:request}))
const wrappers:ReturnType<typeof mount>[]=[]
function render(){const w=mount(MyRepairsView,{global:{stubs:{RouterLink:{template:'<a><slot/></a>'}}}});wrappers.push(w);return w}
const order={id:'ticket',roomId:'room',status:'IN_PROGRESS',version:3,assigneeId:'worker',description:'<img src=x>',history:[]}
beforeEach(()=>{route.params={};request.mockReset();session.user={id:'worker',username:'w',displayName:'worker',departmentId:'d',active:true,roles:['MEMBER'],permissions:['maintenance.repair']}})
afterEach(()=>{wrappers.forEach(w=>w.unmount());wrappers.length=0})
it('queries assignment through the dedicated API and renders plain text',async()=>{
 request.mockResolvedValue({items:[order],total:1,page:0,size:20});const w=render();await flushPromises()
 expect(request).toHaveBeenCalledWith('/repair/work-orders?page=0&size=20');expect(w.text()).toContain('ticket');expect(w.find('img').exists()).toBe(false)
})
it('displays unconfirmed M1 status and ignores a previous account response',async()=>{
 route.params={id:'ticket'};let resolve!:(value:unknown)=>void;request.mockImplementationOnce(()=>new Promise(r=>resolve=r));const w=render()
 request.mockResolvedValue({...order,id:'new-ticket',description:'new task'});session.user={...session.user!,id:'new-worker'};await flushPromises();resolve({...order,description:'old secret'});await flushPromises()
 expect(w.text()).not.toContain('old secret');expect(w.text()).toContain('尚未获得 M1 确认')
})
it('does not query without business permission',async()=>{session.user!.permissions=[];const w=render();await flushPromises();expect(request).not.toHaveBeenCalled();expect(w.text()).toContain('没有维修权限')})
