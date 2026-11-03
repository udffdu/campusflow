import { ApiError } from './api'
export interface ReportInput { roomId:string; faultType:string; description:string }
export interface HistoryEntry {id:string;action:string;actorId:string;fromStatus:string|null;toStatus:string;assigneeId:string|null;remark:string|null;occurredAt:string}
export interface WorkOrder extends ReportInput {id:string;reporterId:string;departmentId:string;status:string;assigneeId:string|null;dispatcherId:string|null;createdAt:string;assignedAt:string|null;updatedAt:string;version:number;history?:HistoryEntry[];maintenanceSync?:string}
export const faultNames:Record<string,string>={LIGHTING:'照明',POWER:'供电',FURNITURE:'家具',HVAC:'空调',OTHER:'其他'}
export const stateName=(status:string)=>({PENDING_DISPATCH:'待派单',ASSIGNED:'已派单',IN_PROGRESS:'维修中',PENDING_ACCEPTANCE:'待验收',COMPLETED:'已完成'} as Record<string,string>)[status]??status
export const maintenanceEnabled=()=>import.meta.env.VITE_M3_INTEGRATION_APPROVED==='true'
export function maintenanceMessage(error:unknown) {
  if(error instanceof ApiError) {
    const labels:Record<number,string>={0:'网络连接失败，提交结果可能尚未确认，请使用原请求重试。',400:'输入不合法或房间不可报修，请核对信息。',401:'登录已失效，请重新登录。',403:'没有操作权限或安全令牌失效，请刷新登录状态后重试。',404:'工单不存在或不属于本人。',409:'请求内容与原提交不一致，请先核对本人记录。',503:'服务暂时不可用，请稍后重试。'}
    return (labels[error.status]??error.message)+(error.requestId?`（请求编号：${error.requestId}）`:'')
  }
  return error instanceof Error?error.message:'操作失败，请重试。'
}
export async function maintenance<T>(path:string,body?:ReportInput|{assigneeId:string;expectedVersion:number}|{expectedVersion:number;remark:string},key?:string):Promise<T> {
  if(!maintenanceEnabled()) throw new Error('报修服务接入尚未开放，请等待入口与权限配置完成。')
  const request=async(url:string,options:RequestInit={})=>{
    let response:Response
    try { response=await fetch('/api/v1/maintenance'+url,{...options,credentials:'same-origin'}) }
    catch { throw new ApiError(0,'NETWORK','连接失败') }
    if(!response.ok) {
      const problem=await response.json().catch(()=>({}))
      if(response.status===401) window.dispatchEvent(new Event('session-expired'))
      throw new ApiError(response.status,problem.code??'REQUEST_FAILED',problem.message??'请求失败',problem.requestId)
    }
    return response.json()
  }
  if(!body) return request(path)
  // Never reuse D's CSRF cache. Fetch M3's token for each submission/retry.
  const csrf=await request('/csrf')
  if(typeof csrf.token!=='string'||!csrf.token||csrf.headerName!=='X-M3-XSRF-TOKEN') throw new Error('报修安全令牌响应无效，未发送报修。')
  return request(path,{method:'POST',headers:{'Content-Type':'application/json','X-M3-XSRF-TOKEN':csrf.token,'Idempotency-Key':key!},body:JSON.stringify(body)})
}
