import { createRouter, createWebHistory } from 'vue-router'
import { can, loadSession, session } from './session'
import LoginView from './views/LoginView.vue'
import OverviewView from './views/OverviewView.vue'
import ResourcesView from './views/ResourcesView.vue'
import UsersView from './views/UsersView.vue'
import NotificationsView from './views/NotificationsView.vue'
import DeliveriesView from './views/DeliveriesView.vue'
import AuditView from './views/AuditView.vue'
import NotFoundView from './views/NotFoundView.vue'
export const router = createRouter({ history: createWebHistory(), routes: [
  {path:'/login', component:LoginView, meta:{title:'登录'}},
  {path:'/', component:OverviewView, meta:{title:'工作台'}},
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
  if (session.user && to.path === '/login') return '/'
  if (to.meta.permission && !can(String(to.meta.permission))) return '/forbidden'
})
router.afterEach(to => { document.title = `${to.meta.title ?? 'CampusFlow'} · CampusFlow` })
window.addEventListener('session-expired', () => { session.user = null; session.unread = 0; void router.replace('/login') })
