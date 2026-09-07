import { createRouter, createWebHistory } from 'vue-router'
import { currentAuthUser, defaultHomeForRole, hasPermission, initializeAuth, isAuthenticated, type PermissionKey } from '@/data/authStore'
import { routeView } from '@/router/routeViews'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    { path: '/', redirect: () => isAuthenticated.value ? defaultHomeForRole() : '/login' },
    { path: '/login', name: 'login', component: routeView('/login'), meta: { title: '登录' } },
    { path: '/change-password', name: 'change-password', component: routeView('/change-password'), meta: { title: '修改临时密码' } },
    { path: '/quotation/overview', name: 'quotation-overview', component: routeView('/quotation/overview'), meta: { title: '报价情况预览', permission: 'allRecords' } },
    { path: '/quotation', name: 'quotation', component: routeView('/quotation'), meta: { title: '我的报价', permission: 'quote' } },
    { path: '/quotation/products', name: 'quotation-products', component: routeView('/quotation/products'), props: { mode: 'products' }, meta: { title: '采购', permission: 'purchase' } },
    { path: '/quotation/suppliers', redirect: '/quotation/products' },
    { path: '/quotation/logistics', name: 'quotation-logistics', component: routeView('/quotation/logistics'), meta: { title: '物流', permission: 'logistics' } },
    { path: '/quotation/members', name: 'quotation-members', component: routeView('/quotation/members'), props: { mode: 'members' }, meta: { title: '财务', permission: 'finance' } },
    { path: '/quotation/my-records', name: 'quotation-my-records', component: routeView('/quotation/my-records'), props: { scope: 'mine' }, meta: { title: '我的报价记录', permissions: ['myRecords', 'allRecords'] } },
    { path: '/quotation/records', name: 'quotation-records', component: routeView('/quotation/records'), props: { scope: 'company' }, meta: { title: '报价记录', permission: 'allRecords' } },
    { path: '/quotation/permissions', name: 'quotation-permissions', component: routeView('/quotation/permissions'), meta: { title: '权限管理', permission: 'permissions' } },
    { path: '/quotation/history', redirect: '/quotation/records' },
    { path: '/:pathMatch(.*)*', redirect: () => isAuthenticated.value ? defaultHomeForRole() : '/login' },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  await initializeAuth()
  if (to.path === '/login') return isAuthenticated.value ? defaultHomeForRole() : true
  if (!isAuthenticated.value) return { path: '/login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  if (currentAuthUser.value.mustChangePassword && to.path !== '/change-password') return '/change-password'
  if (!currentAuthUser.value.mustChangePassword && to.path === '/change-password') return defaultHomeForRole()
  const permission = to.meta.permission as PermissionKey | undefined
  if (permission && !hasPermission(permission)) return defaultHomeForRole()
  const permissions = to.meta.permissions as PermissionKey[] | undefined
  if (permissions && !permissions.some(item => hasPermission(item))) return defaultHomeForRole()
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title || '报价系统')} · 米莱诺报价`
})

export default router
