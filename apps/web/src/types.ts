export interface Page<T> { items: T[]; page: number; size: number; total: number }
export interface Identity { id: string; username: string; displayName: string; email: string|null; departmentId: string; active: boolean; roles: string[]; permissions: string[] }
export interface Department { id: string; code: string; name: string }
export interface Role { code: string; name: string; permissions: string[] }
export interface OpeningHour { day: number; start: string; end: string }
export type BookingScope = 'DEPARTMENT'|'CAMPUS'
export interface Space { id: string; type: string; parentId: string|null; code: string; name: string; departmentId: string|null; capacity: number; features: string[]; openingHours: OpeningHour[]; active: boolean; effectiveActive: boolean; version: number; updatedAt: string; bookingScope?: BookingScope|null }
export interface Notice { id: string; source: string; title: string; body: string; objectType: string; objectId: string; receivedAt: string; readAt: string|null }
export interface Delivery { id: string; source: string; eventId: string; recipientId: string; title: string; status: string; attempts: number; nextAttemptAt: string; lastError: string|null; createdAt: string }
export interface Audit { id: string; source: string; actorId: string; actorName: string; departmentId: string|null; action: string; objectType: string; objectId: string; outcome: string; reasonCode: string|null; requestId: string; occurredAt: string }
export interface Overview { resources: number; rooms: number; seats: number; unread: number; recentResources: Space[] }
export const spaceTypes: Record<string,string> = { CAMPUS: '校区', BUILDING: '楼栋', ROOM: '教室', SEAT: '座位' }
export const roleNames: Record<string,string> = { ADMIN: '系统管理员', SPACE_MANAGER: '空间管理员', AUDITOR: '审计员', MEMBER: '普通成员', SEAT_USER: '座位预约用户' }
export const bookingScopeNames: Record<BookingScope,string> = { DEPARTMENT: '本院系可约', CAMPUS: '全校可约' }
export function featureName(feature: string) { return feature === 'POWER_OUTLET' ? '有插座' : feature }
export const statusNames: Record<string,string> = { PENDING: '等待投递', RETRY: '等待重试', DELIVERED: '已送达', DEAD: '投递失败' }
export function dateTime(value: string) { return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value)) }
