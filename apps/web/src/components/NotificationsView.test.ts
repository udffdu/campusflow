import {flushPromises,mount} from '@vue/test-utils'
import {expect,it,vi} from 'vitest'
import NotificationsView from '../views/NotificationsView.vue'
import {session} from '../session'
const {apiMock}=vi.hoisted(()=>({apiMock:vi.fn()}))
vi.mock('../api',()=>({api:apiMock,message:(e:Error)=>e.message}))
it('renders notification text safely and marks it read through the server',async()=>{
  session.user={id:'u',username:'u',displayName:'成员',email:'u@example.invalid',departmentId:'EIE',active:true,roles:['MEMBER'],permissions:['spaces.read']};session.unread=1
  let read=false
  apiMock.mockImplementation(async(path:string,method:string)=>{
    if(path==='/notifications/unread')return{count:read?0:1}
    if(method==='POST'){read=true;return{ok:true}}
    return{items:[{id:'n1',source:'M1',title:'预约即将开始｜A101 · S01',body:'教室：A101教室（A101）\n座位：01号座位（S01）\n时间：2026年10月30日 14:00–15:00（上海时间）\n\n<img src=x onerror=alert(1)>',objectType:'SEAT_RESERVATION',objectId:'r1',receivedAt:'2026-10-22T08:00:00Z',readAt:read?'2026-10-22T09:00:00Z':null}],page:0,size:20,total:1}
  })
  const wrapper=mount(NotificationsView);await flushPromises()
  expect(wrapper.text()).toContain('A101教室（A101）');expect(wrapper.text()).toContain('01号座位（S01）');expect(wrapper.text()).toContain('2026年10月30日 14:00–15:00');expect(wrapper.find('.notice-message').exists()).toBe(true)
  expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>');expect(wrapper.find('img').exists()).toBe(false)
  const button=wrapper.findAll('button').find(b=>b.text()==='标为已读')!;await button.trigger('click');await flushPromises()
  expect(apiMock).toHaveBeenCalledWith('/notifications/n1/read','POST');expect(session.unread).toBe(0);expect(wrapper.text()).toContain('已读');wrapper.unmount()
})
