import {ApiError} from './api'
import {m1Get} from './m1Api'
import type {QueryRoom} from './m1Api'
export interface RepeatContext {reservationId:string;room:QueryRoom;seat:{id:string;code:string};requiresNewTime:true;canReserve:false}
export interface ReservationHistory {items:ReservationHistoryItem[];size:number;nextCursor:string;serverTime:string}
export interface ReservationIntent {roomId:string;seatId:string;startAt:string;endAt:string}
export interface PendingReservation {userId:string;key:string;intent:ReservationIntent;seatCode:string;roomCode:string}
export interface OwnReservation extends ReservationIntent {id:string;status:string;version:number;resourceVersion:number;stage:'PERSONAL_DEMO'|'PERSONAL';productionReady:false;replayed?:boolean}
export interface ReservationHistoryItem extends OwnReservation {checkedInAt:string|null;completedAt:string|null;usageDurationSeconds:number;violationReason:'NO_SHOW'|null}
export interface UsageSession extends OwnReservation {checkedInAt:string;completedAt:string|null;serverTime:string;idempotent?:boolean}
export interface Capabilities {stage:string;canCreateDemoReservation:boolean;canCreateReservation?:boolean;uncheckedSources?:string[];advanceMode?:string;advanceDays?:number;durationHours?:number;canReadOwn:boolean;productionReady:false}
export interface RoomCheckInCode {roomId:string;roomCode:string;roomName:string;code:string;validDate:string;expiresAt:string;timeZone:'Asia/Shanghai'}
export type ReservationStatus='CONFIRMED'|'CHECKED_IN'|'COMPLETED'|'NO_SHOW'|'DISRUPTED'
export function shanghaiDateRange(fromDate:string,toDate:string):{from:string;to:string}{
  const start=dateParts(fromDate),end=dateParts(toDate)
  const from=Date.UTC(start.year,start.month-1,start.day)-8*60*60*1000
  const to=Date.UTC(end.year,end.month-1,end.day+1)-8*60*60*1000
  if(from>=to)throw new Error('结束日期不能早于开始日期')
  return {from:new Date(from).toISOString(),to:new Date(to).toISOString()}
}
function dateParts(value:string){
  const match=/^(\d{4})-(\d{2})-(\d{2})$/.exec(value);if(!match)throw new Error('请选择有效日期')
  const year=Number(match[1]),month=Number(match[2]),day=Number(match[3]),probe=new Date(Date.UTC(year,month-1,day))
  if(probe.getUTCFullYear()!==year||probe.getUTCMonth()!==month-1||probe.getUTCDate()!==day)throw new Error('请选择有效日期')
  return {year,month,day}
}
const prefix='campusflow.pending-reservation.'
export function clearPendingReservations(){for(let i=sessionStorage.length-1;i>=0;i--){const key=sessionStorage.key(i);if(key?.startsWith(prefix))sessionStorage.removeItem(key)}}
export function pendingFor(userId:string):PendingReservation|undefined {
  try{const p=JSON.parse(sessionStorage.getItem(prefix+userId)??'null');if(p?.userId===userId&&typeof p.key==='string'&&p.intent&&['roomId','seatId','startAt','endAt'].every(k=>typeof p.intent[k]==='string'))return p}catch{}
}
export function savePending(p:PendingReservation){sessionStorage.setItem(prefix+p.userId,JSON.stringify(p))}
export function validOwnReservation(value:unknown):value is OwnReservation {const v=value as OwnReservation;return !!v&&typeof v.id==='string'&&!!v.id&&typeof v.seatId==='string'&&typeof v.roomId==='string'&&Number.isFinite(Date.parse(v.startAt))&&Number.isFinite(Date.parse(v.endAt))&&['CONFIRMED','CHECKED_IN','COMPLETED','NO_SHOW','DISRUPTED'].includes(v.status)&&v.version>0&&v.resourceVersion>0&&['PERSONAL_DEMO','PERSONAL'].includes(v.stage)&&v.productionReady===false}
export function validReservationHistoryItem(value:unknown):value is ReservationHistoryItem {
  const v=value as ReservationHistoryItem
  return validOwnReservation(v)&&(v.checkedInAt===null||Number.isFinite(Date.parse(v.checkedInAt)))
    &&(v.completedAt===null||Number.isFinite(Date.parse(v.completedAt)))
    &&Number.isInteger(v.usageDurationSeconds)&&v.usageDurationSeconds>=0
    &&(v.violationReason===null||v.violationReason==='NO_SHOW')
    &&(!['CHECKED_IN','COMPLETED'].includes(v.status)||v.checkedInAt!==null)
    &&(v.status!=='COMPLETED'||v.completedAt!==null)
    &&(v.status!=='NO_SHOW'||v.violationReason==='NO_SHOW')
}
export function finishPending(userId:string){sessionStorage.removeItem(prefix+userId)}
window.addEventListener('session-expired',clearPendingReservations)
export async function submitReservation(p:PendingReservation):Promise<OwnReservation>{
  const csrf=await m1Get<{token:string;headerName:string}>('/csrf')
  let response:Response
  try{response=await fetch('/api/m1/v1/reservations',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-M1-CSRF-Token':csrf.token,'Idempotency-Key':p.key},body:JSON.stringify(p.intent)})}
  catch{throw new ApiError(0,'RESULT_UNKNOWN','尚未确认提交结果，请保留原请求并查询结果或重试')}
  if(!response.ok){const error=await response.json().catch(()=>({}));if(response.status===401)window.dispatchEvent(new Event('session-expired'));throw new ApiError(response.status,error.code??'RESULT_UNKNOWN',error.message??'尚未确认提交结果，请保留原请求重试',error.requestId)}
  try{const value=await response.json();if(!validOwnReservation(value))throw new Error('incomplete response');return value}catch{throw new ApiError(0,'RESULT_UNKNOWN','响应未完整收到，请使用原请求恢复结果')}
}
export async function checkInReservation(id:string,code:string):Promise<UsageSession>{
  if(!/^[0-9]{6}$/.test(code))throw new ApiError(0,'CHECK_IN_CODE_INVALID','请输入 6 位数字签到码')
  const csrf=await m1Get<{token:string;headerName:string}>('/csrf')
  let response:Response
  try{response=await fetch(`/api/m1/v1/reservations/${encodeURIComponent(id)}/check-in`,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-M1-CSRF-Token':csrf.token},body:JSON.stringify({code})})}
  catch{throw new ApiError(0,'NETWORK','签到服务连接失败，请保留签到码并重试')}
  if(!response.ok){const error=await response.json().catch(()=>({}));if(response.status===401)window.dispatchEvent(new Event('session-expired'));throw new ApiError(response.status,error.code??'CHECK_IN_FAILED',error.message??'签到失败，请稍后重试',error.requestId)}
  const value=await response.json().catch(()=>null)
  if(!validUsageSession(value))throw new ApiError(0,'RESULT_UNKNOWN','签到结果未完整收到，请刷新预约记录确认')
  return value
}
export async function usageSession(id:string):Promise<UsageSession>{
  const value=await m1Get<UsageSession>(`/reservations/${encodeURIComponent(id)}/usage`)
  if(!validUsageSession(value))throw new ApiError(0,'RESULT_UNKNOWN','使用状态响应不完整，请刷新后重试')
  return value
}
export async function leaveSeat(id:string):Promise<UsageSession>{
  const csrf=await m1Get<{token:string;headerName:string}>('/csrf')
  let response:Response
  try{response=await fetch(`/api/m1/v1/reservations/${encodeURIComponent(id)}/leave-seat`,{method:'POST',credentials:'same-origin',headers:{'X-M1-CSRF-Token':csrf.token}})}
  catch{throw new ApiError(0,'NETWORK','退座服务连接失败，请刷新使用状态确认后重试')}
  if(!response.ok){const error=await response.json().catch(()=>({}));if(response.status===401)window.dispatchEvent(new Event('session-expired'));throw new ApiError(response.status,error.code??'LEAVE_SEAT_FAILED',error.message??'退座失败，请稍后重试',error.requestId)}
  const value=await response.json().catch(()=>null)
  if(!validUsageSession(value))throw new ApiError(0,'RESULT_UNKNOWN','退座结果尚未完整收到，请刷新使用状态确认')
  return value
}
function validUsageSession(value:unknown):value is UsageSession{
  const v=value as UsageSession
  return validOwnReservation(v)&&['CHECKED_IN','COMPLETED'].includes(v.status)
    &&Number.isFinite(Date.parse(v.checkedInAt))&&Number.isFinite(Date.parse(v.serverTime))
    &&(v.completedAt===null||Number.isFinite(Date.parse(v.completedAt)))
}
export function definitiveFailure(e:unknown){return e instanceof ApiError&&['USER_TIME_CONFLICT','SEAT_TIME_CONFLICT','RESOURCE_INACTIVE','OUTSIDE_OPENING_HOURS','IN_PAST','ADVANCE_WINDOW','DURATION_LIMIT','NOT_WHOLE_HOUR','INVALID_INTERVAL'].includes(e.code)}
