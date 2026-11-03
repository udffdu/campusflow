import {flushPromises,mount} from '@vue/test-utils'
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import NotificationToastHost from './NotificationToastHost.vue'
import {session} from '../session'

const {apiMock,push,refreshUnread}=vi.hoisted(()=>({apiMock:vi.fn(),push:vi.fn(),refreshUnread:vi.fn()}))
vi.mock('../api',()=>({api:apiMock}))
vi.mock('vue-router',()=>({useRouter:()=>({push})}))
vi.mock('../session',async original=>({...await original<typeof import('../session')>(),refreshUnread}))

beforeEach(()=>{vi.useFakeTimers();apiMock.mockReset();push.mockReset();refreshUnread.mockReset();session.user={id:'u',username:'student',displayName:'同学',email:'student@example.invalid',departmentId:'d',active:true,roles:['SEAT_USER'],permissions:[]}})
afterEach(()=>{session.user=null;vi.useRealTimers()})

it('suppresses existing history and pops up each newly delivered notice once',async()=>{
  const existing={id:'old',source:'M1',title:'历史通知',body:'旧内容',objectType:'SEAT_RESERVATION',objectId:'r0',receivedAt:'2026-10-30T01:00:00Z',readAt:null}
  const fresh={...existing,id:'new',title:'预约即将开始｜A101 · S01',body:'教室：A101研讨室（A101）\n座位：S01\n时间：14:00–15:00'}
  apiMock.mockResolvedValueOnce({items:[existing],page:0,size:10,total:1}).mockResolvedValue({items:[fresh,existing],page:0,size:10,total:2})
  const wrapper=mount(NotificationToastHost,{global:{stubs:{Teleport:true}}});await flushPromises()
  expect(wrapper.text()).not.toContain('历史通知')
  await vi.advanceTimersByTimeAsync(3000);await flushPromises()
  expect(wrapper.text()).toContain(fresh.title);expect(wrapper.text()).toContain('A101研讨室');expect(refreshUnread).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(3000);await flushPromises();expect(wrapper.findAll('.notification-toast')).toHaveLength(1)
  await wrapper.find('.notification-toast .text-link').trigger('click');await flushPromises();expect(push).toHaveBeenCalledWith('/notifications')
  wrapper.unmount()
})
