import {flushPromises,mount} from '@vue/test-utils'
import {beforeEach,afterEach,expect,it,vi} from 'vitest'
import RoomsView from '../views/RoomsView.vue'
import {session} from '../session'
import {toInstant} from '../rooms'
const {apiMock,pagesMock}=vi.hoisted(()=>({apiMock:vi.fn(),pagesMock:vi.fn()}))
vi.mock('../api',()=>({api:apiMock,allPages:pagesMock,message:(e:Error)=>e.message}))
vi.mock('vue-router',()=>({useRoute:()=>({query:{}})}))
const app={id:'a',applicantId:'applicant',applicantName:'申请人',departmentId:'EIE',roomId:'r',roomName:'A101',title:'课程讨论',purpose:'<img src=x onerror=alert(1)>',attendees:6,startAt:'2099-11-03T02:00:00Z',endAt:'2099-11-03T04:00:00Z',deadline:'2099-11-03T02:00:00Z',steps:[{name:'院系初审',permission:'rooms.review'},{name:'教室终审',permission:'rooms.approve'}],currentStep:0,status:'PENDING',version:4,attempts:0,needsAttention:false}
let wrapper:ReturnType<typeof mount>|null=null
beforeEach(()=>{
  session.user={id:'reviewer',username:'reviewer',displayName:'审核员',departmentId:'EIE',active:true,roles:['ROOM_REVIEWER'],permissions:['rooms.read','rooms.review']}
  apiMock.mockImplementation(async(path:string)=>path==='/room-applications/a'?{application:{...app},history:[],deliveries:[]}:{items:[{...app}],page:0,size:20,total:1})
})
afterEach(()=>{wrapper?.unmount();wrapper=null;vi.clearAllMocks()})
async function open(){wrapper=mount(RoomsView,{global:{stubs:{Modal:{template:'<div><slot/></div>'}}}});await flushPromises();await wrapper.findAll('button').find(b=>b.text()==='查看详情')!.trigger('click');await flushPromises();return wrapper}
it('uses Shanghai time independent of the browser timezone',()=>{expect(toInstant('2026-11-03T10:30')).toBe('2026-11-03T02:30:00.000Z')})
it('renders safe detail text and submits the exact visible version and step',async()=>{
  const w=await open();expect(w.find('img').exists()).toBe(false);expect(w.text()).toContain('<img src=x onerror=alert(1)>')
  await w.findAll('button').find(b=>b.text()==='通过本节点')!.trigger('click');await w.find('textarea').setValue('用途合理')
  apiMock.mockImplementationOnce(async()=>({application:{...app,currentStep:1,version:5},history:[],deliveries:[]}))
  await w.find('form').trigger('submit');await flushPromises()
  expect(apiMock).toHaveBeenCalledWith('/room-applications/a/decisions','POST',{expectedVersion:4,step:0,decision:'APPROVE',comment:'用途合理'})
})
it('does not offer self approval even when the applicant has review permissions',async()=>{
  session.user!.id='applicant';const w=await open();expect(w.findAll('button').some(b=>b.text()==='通过本节点')).toBe(false);expect(w.text()).toContain('撤销申请')
})
it('shows processing truthfully and preserves feedback when a version conflict occurs',async()=>{
  const w=await open();await w.findAll('button').find(b=>b.text()==='驳回')!.trigger('click');await w.find('textarea').setValue('需要补充说明')
  apiMock.mockRejectedValueOnce(new Error('申请已被其他操作更新，请刷新后重试'))
  await w.find('form').trigger('submit');await flushPromises();expect(w.find('[role="alert"]').text()).toContain('请刷新后重试');expect(w.find('textarea').element.value).toBe('需要补充说明')
})
it('a pending occupancy is never presented as a successful reservation',async()=>{
  apiMock.mockImplementation(async(path:string)=>path==='/room-applications/a'?{application:{...app,status:'PROCESSING',currentStep:2},history:[],deliveries:[]}:{items:[{...app,status:'PROCESSING',currentStep:2}],total:1})
  const w=await open();expect(w.text()).toContain('此时仍未预约成功');expect(w.findAll('button').some(b=>b.text()==='通过本节点')).toBe(false)
})
