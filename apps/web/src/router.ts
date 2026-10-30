import { createRouter, createWebHistory } from 'vue-router'
import { can, landingPath, loadSession, onSessionChange, refreshUnread, session } from './session'
import SeatQueryView from './views/SeatQueryView.vue'
import MyReservationsView from './views/MyReservationsView.vue'
import LoginView from './views/LoginView.vue'
import OverviewView from './views/OverviewView.vue'
import ResourcesView from './views/ResourcesView.vue'
import UsersView from './views/UsersView.vue'
import NotificationsView from './views/NotificationsView.vue'
import DeliveriesView from './views/DeliveriesView.vue'
import AuditView from './views/AuditView.vue'
import NotFoundView from './views/NotFoundView.vue'
import RoomCheckInDisplayView from './views/RoomCheckInDisplayView.vue'
import SeatUsageView from './views/SeatUsageView.vue'
export const router = createRouter({ history: createWebHistory(), routes: [
  {path:'/login', component:LoginView, meta:{title:'登录'}},
  {path:'/', component:OverviewView, meta:{title:'工作台'}},
  {path:'/seat-reservations',component:SeatQueryView,props:{booking:true},meta:{title:'座位预约',permission:'seats.availability.read'}},
  {path:'/seat-reservation-demo',component:SeatQueryView,props:{demo:true},meta:{title:'个人预约演示',permission:'seats.availability.read'}},
  {path:'/seat-query',component:SeatQueryView,meta:{title:'座位查询',permission:'seats.availability.read'}},
  {path:'/my-reservations',component:MyReservationsView,meta:{title:'我的预约',permission:'seats.reservations.read.own'}},
  {path:'/room-checkin-display/:roomId',component:RoomCheckInDisplayView,meta:{title:'教室签到码',permission:'spaces.write'}},
  {path:'/seat-usage/:reservationId',component:SeatUsageView,meta:{title:'座位使用状态',permission:'seats.reservations.read.own'}},
  {path:'/resources', component:ResourcesView, meta:{title:'空间资源',permission:'spaces.read'}},
  {path:'/users', component:UsersView, meta:{title:'账号与权限',permission:'users.manage'}},
  {path:'/notifications', component:NotificationsView, meta:{title:'通知中心'}},
  {path:'/deliveries', component:DeliveriesView, meta:{title:'通知投递',permission:'notifications.manage'}},
  {path:'/audit', component:AuditView, meta:{title:'操作审计',permission:'audit.read'}},
  {path:'/forbidden',component:NotFoundView,meta:{title:'访问受限'}},
  {path:'/:pathMatch(.*)*',component:NotFoundView,meta:{title:'页面不存在'}},
] })
router.beforeEach(async to => {
  if (!session.ready) { try { await loadSession() } catch { if (to.path !== '/login') return '/login' } }
  if (!session.user && to.path !== '/login') return '/login'
  if (session.user && to.path === '/login') return landingPath()
  if (to.meta.permission && !can(String(to.meta.permission))) return '/forbidden'
})
router.afterEach(to => { document.title = `${to.meta.title ?? 'CampusFlow'} · CampusFlow` })
window.addEventListener('session-expired', () => { session.user = null; session.unread = 0; void router.replace('/login') })
let synchronizingSession=false
onSessionChange(() => {
  if(synchronizingSession)return
  synchronizingSession=true
  const previous=session.user?.id
  void loadSession().then(async()=>{
    session.unread=0
    if(!session.user){await router.replace('/login');return}
    const permission=router.currentRoute.value.meta.permission
    if(previous!==session.user.id||(permission&&!can(String(permission))))await router.replace(landingPath())
    void refreshUnread().catch(()=>{})
  }).catch(()=>{session.user=null;session.unread=0;void router.replace('/login')}).finally(()=>{synchronizingSession=false})
})
