import {flushPromises,mount} from '@vue/test-utils'
import {afterEach,expect,it,vi} from 'vitest'
import {ApiError} from '../api'
import RoomCheckInDisplayView from '../views/RoomCheckInDisplayView.vue'
const {get}=vi.hoisted(()=>({get:vi.fn()}))
vi.mock('../m1Api',()=>({m1Get:get}))
vi.mock('vue-router',()=>({useRoute:()=>({params:{roomId:'room-1'}})}))
afterEach(()=>vi.useRealTimers())
it('loads only the route room and renders the six digit code with its Shanghai expiry',async()=>{
  get.mockResolvedValue({roomId:'room-1',roomCode:'A101',roomName:'第一教室',code:'012345',validDate:'2026-10-30',expiresAt:'2026-10-30T16:00:00Z',timeZone:'Asia/Shanghai'})
  const wrapper=mount(RoomCheckInDisplayView);await flushPromises()
  expect(get).toHaveBeenCalledWith('/rooms/room-1/check-in-code');expect(wrapper.text()).toContain('012345');expect(wrapper.text()).toContain('A101 · 第一教室');expect(wrapper.text()).toContain('2026-10-30（上海）');wrapper.unmount()
})
it('rejects a mismatched room response instead of displaying a leaked code',async()=>{
  get.mockResolvedValue({roomId:'other',roomCode:'X',roomName:'其他教室',code:'999999',validDate:'2026-10-30',expiresAt:'2026-10-30T16:00:00Z',timeZone:'Asia/Shanghai'})
  const wrapper=mount(RoomCheckInDisplayView);await flushPromises();expect(wrapper.text()).toContain('签到码响应不完整');expect(wrapper.text()).not.toContain('999999');wrapper.unmount()
})
it('explains that M1 must be running when the code service is unavailable',async()=>{
  get.mockRejectedValue(new ApiError(500,'QUERY_FAILED','查询失败，请稍后重试'))
  const wrapper=mount(RoomCheckInDisplayView);await flushPromises()
  expect(wrapper.text()).toContain('签到码服务不可用，请确认 M1 服务已启动后重试');wrapper.unmount()
})
