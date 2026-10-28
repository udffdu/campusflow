import {ApiError} from './api'
import {m1Get} from './m1Api'
export interface ReservationIntent {roomId:string;seatId:string;startAt:string;endAt:string}
export interface PendingReservation {userId:string;key:string;intent:ReservationIntent;seatCode:string;roomCode:string}
export interface OwnReservation extends ReservationIntent {id:string;status:string;version:number;resourceVersion:number;stage:'PERSONAL_DEMO';productionReady:false;replayed?:boolean}
export interface Capabilities {stage:string;canCreateDemoReservation:boolean;canReadOwn:boolean;productionReady:false}
const prefix='campusflow.pending-reservation.'
export function clearPendingReservations(){for(let i=sessionStorage.length-1;i>=0;i--){const key=sessionStorage.key(i);if(key?.startsWith(prefix))sessionStorage.removeItem(key)}}
export function pendingFor(userId:string):PendingReservation|undefined {
  try{const p=JSON.parse(sessionStorage.getItem(prefix+userId)??'null');if(p?.userId===userId&&typeof p.key==='string'&&p.intent&&['roomId','seatId','startAt','endAt'].every(k=>typeof p.intent[k]==='string'))return p}catch{}
}
export function savePending(p:PendingReservation){sessionStorage.setItem(prefix+p.userId,JSON.stringify(p))}
export function validOwnReservation(value:unknown):value is OwnReservation {const v=value as OwnReservation;return !!v&&typeof v.id==='string'&&!!v.id&&typeof v.seatId==='string'&&typeof v.roomId==='string'&&Number.isFinite(Date.parse(v.startAt))&&Number.isFinite(Date.parse(v.endAt))&&['CONFIRMED','CHECKED_IN','COMPLETED','NO_SHOW','DISRUPTED'].includes(v.status)&&v.version>0&&v.resourceVersion>0&&v.stage==='PERSONAL_DEMO'&&v.productionReady===false}
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
export function definitiveFailure(e:unknown){return e instanceof ApiError&&['USER_TIME_CONFLICT','SEAT_TIME_CONFLICT','RESOURCE_INACTIVE','OUTSIDE_OPENING_HOURS','IN_PAST','ADVANCE_WINDOW','DURATION_LIMIT','NOT_WHOLE_HOUR','INVALID_INTERVAL'].includes(e.code)}
