import { ApiError } from './api'
export interface QueryRoom { id:string; code:string; name:string; bookingScope:'DEPARTMENT'|'CAMPUS'; openingHours:{day:number;start:string;end:string}[] }
export interface QueryPage<T> {items:T[];page:number;size:number;total:number}
export interface SeatCandidates extends QueryPage<{id:string;code:string;hasPowerOutlet:boolean}> {
  roomId:string;startAt:string;endAt:string;reason:'NONE'|'ROOM_INACTIVE'|'OUTSIDE_OPENING_HOURS'|'USER_TIME_CONFLICT';stage:'CANDIDATE_ONLY';canReserve:false
}
/** Read-only M1 requests. No D service key or write-CSRF headers belong in the browser. */
export async function m1Get<T>(path:string):Promise<T> {
  let response:Response
  try {response=await fetch(`/api/m1/v1${path}`,{credentials:'same-origin'})}
  catch {throw new ApiError(0,'NETWORK','查询服务连接失败，请稍后重试')}
  if(!response.ok){
    const problem=await response.json().catch(()=>({}))
    if(response.status===401)window.dispatchEvent(new Event('session-expired'))
    throw new ApiError(response.status,problem.code??'QUERY_FAILED',problem.message??'查询失败，请稍后重试',problem.requestId)
  }
  return response.json()
}
/** datetime-local fields always mean campus (Shanghai) time, independent of browser timezone. */
export function campusInstant(value:string):string {
  if(!/^\d{4}-\d\d-\d\dT\d\d:00$/.test(value))throw new Error('请选择上海时间的整点')
  const date=new Date(`${value}:00+08:00`)
  if(!Number.isFinite(date.getTime()))throw new Error('时间无效')
  return date.toISOString().replace('.000Z','Z')
}
