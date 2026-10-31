import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {beforeEach,expect,it,vi} from 'vitest'
import OperationsDashboardView from '../views/OperationsDashboardView.vue'
import type {OperationsResponse} from '../operations'

const {query}=vi.hoisted(()=>({query:vi.fn()}));vi.mock('../operations',async importOriginal=>({...await importOriginal<typeof import('../operations')>(),queryOperations:(...args:unknown[])=>query(...args)}))
const id=(n:number)=>`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`
function page(nextCursor='cursor-2'):OperationsResponse{return {scope:{departmentId:id(1)},criteria:{from:'2026-10-01T00:00:00Z',to:'2026-11-01T00:00:00Z'},resources:{rooms:[{id:id(2),code:'A101',name:'第一自习室'}],seats:[{id:id(3),roomId:id(2),code:'01'}]},items:[{id:id(4),roomId:id(2),roomCode:'A101',seatId:id(3),seatCode:'01',startAt:'2026-10-30T01:00:00Z',endAt:'2026-10-30T02:00:00Z',status:'NO_SHOW',checkedInAt:null,completedAt:null,usageDurationSeconds:0,createdAt:'2026-10-29T00:00:00Z',updatedAt:'2026-10-30T01:20:00Z'}],size:20,nextCursor,total:4,summary:{total:4,byStatus:{CONFIRMED:1,CHECKED_IN:0,COMPLETED:2,NO_SHOW:1,DISRUPTED:0},checkedIn:2,violations:1,eligible:3,checkInRate:2/3,reservedDurationSeconds:10800,actualUsageSeconds:7200,utilizationRate:2/3,byRoom:[{id:id(2),code:'A101',count:4}],bySeat:[{id:id(3),code:'01',count:4}]},serverTime:'2026-10-31T00:00:00Z'}}
let wrapper:VueWrapper
beforeEach(()=>{query.mockReset();query.mockResolvedValue(page())})
async function open(){wrapper=mount(OperationsDashboardView);await flushPromises()}

it('shows decision metrics, resource rankings and privacy boundary',async()=>{await open();expect(wrapper.text()).toContain('66.7%');expect(wrapper.text()).toContain('2 小时 0 分');expect(wrapper.text()).toContain('违约');expect(wrapper.text()).toContain('A101');expect(wrapper.text()).toContain('不包含学生姓名');expect(wrapper.text()).not.toContain('student')})
it('sends filters and uses the opaque cursor for stable next-page reads',async()=>{query.mockResolvedValueOnce(page()).mockResolvedValueOnce(page()).mockResolvedValueOnce(page(''));await open();await wrapper.findAll('select')[0]!.setValue(id(2));await wrapper.get('form').trigger('submit');await flushPromises();const filtered=query.mock.calls.at(-1)![0] as URLSearchParams;expect(filtered.get('roomId')).toBe(id(2));await wrapper.findAll('button').find(button=>button.text()==='下一页')!.trigger('click');await flushPromises();const next=query.mock.calls.at(-1)![0] as URLSearchParams;expect(next.get('cursor')).toBe('cursor-2');expect(wrapper.text()).toContain('第 2 页')})
it('keeps an explicit error state instead of showing misleading zero metrics',async()=>{query.mockRejectedValue(new Error('暂时失败'));await open();expect(wrapper.get('[role="alert"]').text()).toContain('暂时失败');expect(wrapper.text()).not.toContain('预约记录0')})
