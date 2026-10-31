import { m1Get } from './m1Api'

export const operationStatuses=['CONFIRMED','CHECKED_IN','COMPLETED','NO_SHOW','DISRUPTED'] as const
export type OperationStatus=typeof operationStatuses[number]
export interface OperationResource {id:string;code:string;name?:string;roomId?:string}
export interface OperationCount {id:string;code:string|null;count:number}
export interface OperationEntry {
  id:string;roomId:string;roomCode:string|null;seatId:string;seatCode:string|null;startAt:string;endAt:string
  status:OperationStatus;checkedInAt:string|null;completedAt:string|null;usageDurationSeconds:number;createdAt:string;updatedAt:string
}
export interface OperationsResponse {
  scope:{departmentId:string};criteria:{from:string;to:string;roomId?:string;seatId?:string;status?:OperationStatus}
  resources:{rooms:OperationResource[];seats:OperationResource[]};items:OperationEntry[];size:number;nextCursor:string;total:number
  summary:{total:number;byStatus:Record<OperationStatus,number>;checkedIn:number;violations:number;eligible:number;checkInRate:number|null
    reservedDurationSeconds:number;actualUsageSeconds:number;utilizationRate:number|null;byRoom:OperationCount[];bySeat:OperationCount[]}
  serverTime:string
}

// PostgreSQL and java.util.UUID accept the complete UUID textual domain.  IDs
// imported from campus master data are not required to encode an RFC version
// or variant, so the browser boundary must not reject otherwise valid IDs.
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const instant=(value:unknown):value is string=>typeof value==='string'&&Number.isFinite(Date.parse(value))
const integer=(value:unknown):value is number=>Number.isSafeInteger(value)&&Number(value)>=0
const object=(value:unknown):value is Record<string,unknown>=>!!value&&typeof value==='object'&&!Array.isArray(value)
function fail():never{throw new Error('预约运营响应不完整，请刷新重试')}
function rate(value:unknown):value is number|null{return value===null||(typeof value==='number'&&Number.isFinite(value)&&value>=0&&value<=1)}
function count(value:unknown):value is OperationCount{return object(value)&&typeof value.id==='string'&&uuid.test(value.id)&&
  (value.code===null||typeof value.code==='string')&&integer(value.count)}

export function parseOperations(value:unknown):OperationsResponse {
  if(!object(value)||!object(value.scope)||typeof value.scope.departmentId!=='string'||!uuid.test(value.scope.departmentId)||
    !object(value.criteria)||!object(value.resources)||!Array.isArray(value.resources.rooms)||!Array.isArray(value.resources.seats)||
    !Array.isArray(value.items)||!object(value.summary)||!integer(value.size)||!integer(value.total)||typeof value.nextCursor!=='string'||!instant(value.serverTime))fail()
  const rooms=value.resources.rooms, seats=value.resources.seats, items=value.items, summary=value.summary
  if(!rooms.every(room=>object(room)&&typeof room.id==='string'&&uuid.test(room.id)&&typeof room.code==='string'&&typeof room.name==='string')||
    !seats.every(seat=>object(seat)&&typeof seat.id==='string'&&uuid.test(seat.id)&&typeof seat.roomId==='string'&&uuid.test(seat.roomId)&&typeof seat.code==='string'))fail()
  if(!items.every(item=>object(item)&&!('userId'in item)&&!('username'in item)&&!('email'in item)&&
    typeof item.id==='string'&&uuid.test(item.id)&&typeof item.roomId==='string'&&uuid.test(item.roomId)&&
    typeof item.seatId==='string'&&uuid.test(item.seatId)&&(item.roomCode===null||typeof item.roomCode==='string')&&
    (item.seatCode===null||typeof item.seatCode==='string')&&instant(item.startAt)&&instant(item.endAt)&&
    operationStatuses.includes(item.status as OperationStatus)&&(item.checkedInAt===null||instant(item.checkedInAt))&&
    (item.completedAt===null||instant(item.completedAt))&&integer(item.usageDurationSeconds)&&instant(item.createdAt)&&instant(item.updatedAt)))fail()
  const byStatus=summary.byStatus;if(!object(byStatus))fail()
  if(!operationStatuses.every(status=>integer(byStatus[status]))||!integer(summary.total)||
    !integer(summary.checkedIn)||!integer(summary.violations)||!integer(summary.eligible)||!rate(summary.checkInRate)||
    !integer(summary.reservedDurationSeconds)||!integer(summary.actualUsageSeconds)||!rate(summary.utilizationRate)||
    !Array.isArray(summary.byRoom)||!summary.byRoom.every(count)||!Array.isArray(summary.bySeat)||!summary.bySeat.every(count)||
    summary.total!==value.total||operationStatuses.reduce((sum,status)=>sum+Number(byStatus[status]),0)!==summary.total||
    summary.checkedIn!==Number(byStatus.CHECKED_IN)+Number(byStatus.COMPLETED)||
    summary.violations!==Number(byStatus.NO_SHOW)||summary.eligible!==summary.checkedIn+summary.violations)fail()
  return value as unknown as OperationsResponse
}

export async function queryOperations(parameters:URLSearchParams){return parseOperations(await m1Get<unknown>(`/operations/reservations?${parameters}`))}

export function shanghaiInstant(value:string):string {
  if(!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value))throw new Error('请选择完整的上海日期与时间')
  const date=new Date(`${value}:00+08:00`);if(!Number.isFinite(date.getTime()))throw new Error('时间无效')
  return date.toISOString().replace('.000Z','Z')
}
