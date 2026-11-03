export interface ApprovalStep {name:string;permission:string}
export interface RoomApplication {
  id:string;applicantId:string;applicantName:string;departmentId:string;roomId:string;roomName:string;
  title:string;purpose:string;attendees:number;startAt:string;endAt:string;deadline:string;
  steps:ApprovalStep[];currentStep:number;status:string;version:number;operationVersion:number;
  attempts:number;nextAttemptAt:string;lastError:string|null;needsAttention:boolean;createdAt:string;updatedAt:string
}
export interface ApplicationHistory {id:string;version:number;action:string;actorId:string|null;actorName:string;step:number|null;comment:string;occurredAt:string}
export interface ApplicationDetail {application:RoomApplication;history:ApplicationHistory[];deliveries:{id:string;kind:string;status:string;attempts:number;lastError:string|null}[]}
export interface RoomPolicy {steps:ApprovalStep[];approvalHours:number;maxHours:number;advanceDays:number;minuteStep:number;minLeadMinutes:number;timezone:string}
export const applicationStates:Record<string,string>={PENDING:'待审批',PROCESSING:'占用处理中',APPROVED:'已批准',REJECTED:'已驳回',EXPIRED:'已超时撤销',CANCELLED:'已撤销',CONFLICT:'资源冲突',RELEASING:'释放确认中'}
export const historyActions:Record<string,string>={SUBMITTED:'提交申请',STEP_APPROVED:'节点通过',REJECTED:'驳回申请',EXPIRED:'超时自动撤销',CANCELLED:'撤销申请',RELEASE_REQUESTED:'开始释放',RELEASE_CONFIRMED:'释放已确认',OCCUPANCY_CONFIRMED:'占用已确认',RESOURCE_CONFLICT:'资源冲突',RETRY_REQUESTED:'请求恢复'}
export function shanghaiTime(value:string) {return new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(value))}
export function toInstant(value:string) {return new Date(`${value}:00+08:00`).toISOString()}
