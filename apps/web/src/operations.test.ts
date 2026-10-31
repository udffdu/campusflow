import {describe,expect,it,vi} from 'vitest'
import {parseOperations,shanghaiInstant} from './operations'

const id=(n:number)=>`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`
function response(){return {scope:{departmentId:id(1)},criteria:{from:'2026-10-01T00:00:00Z',to:'2026-11-01T00:00:00Z'},resources:{rooms:[{id:id(2),code:'A101',name:'第一自习室'}],seats:[{id:id(3),roomId:id(2),code:'01'}]},items:[{id:id(4),roomId:id(2),roomCode:'A101',seatId:id(3),seatCode:'01',startAt:'2026-10-30T01:00:00Z',endAt:'2026-10-30T02:00:00Z',status:'COMPLETED',checkedInAt:'2026-10-30T01:05:00Z',completedAt:'2026-10-30T01:50:00Z',usageDurationSeconds:2700,createdAt:'2026-10-29T00:00:00Z',updatedAt:'2026-10-30T01:50:00Z'}],size:20,nextCursor:'',total:1,summary:{total:1,byStatus:{CONFIRMED:0,CHECKED_IN:0,COMPLETED:1,NO_SHOW:0,DISRUPTED:0},checkedIn:1,violations:0,eligible:1,checkInRate:1,reservedDurationSeconds:3600,actualUsageSeconds:2700,utilizationRate:.75,byRoom:[{id:id(2),code:'A101',count:1}],bySeat:[{id:id(3),code:'01',count:1}]},serverTime:'2026-10-31T00:00:00Z'}}

describe('operations response boundary',()=>{
  it('accepts aggregate data without any reservation owner identity',()=>{expect(parseOperations(response()).summary.utilizationRate).toBe(.75)})
  it('accepts stable campus master-data UUIDs that do not encode an RFC version or variant',()=>{
    const value=response();value.scope.departmentId='10000000-0000-0000-0000-000000000001'
    expect(parseOperations(value).scope.departmentId).toBe('10000000-0000-0000-0000-000000000001')
  })
  it('rejects leaked personal identity and inconsistent aggregates',()=>{const leaked=response() as any;leaked.items[0].userId=id(9);expect(()=>parseOperations(leaked)).toThrow();const bad=response();bad.summary.total=2;expect(()=>parseOperations(bad)).toThrow()})
  it('interprets form values in Shanghai independently of browser timezone',()=>{vi.useFakeTimers();vi.setSystemTime(new Date('2026-01-01'));expect(shanghaiInstant('2026-10-31T09:30')).toBe('2026-10-31T01:30:00Z');expect(()=>shanghaiInstant('2026-10-31')).toThrow();vi.useRealTimers()})
})
