import { createRouter, createWebHistory } from 'vue-router'
import { currentAuthUser, defaultHomeForRole, hasPermission, initializeAuth, isAuthenticated, type PermissionKey } from '@/data/authStore'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    { path: '/', redirect: () => isAuthenticated.value ? defaultHomeForRole() : '/login' },
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { title: '登录' } },
    { path: '/migration/export', name: 'migration-export', component: () => import('@/views/LegacyMigrationExportView.vue'), meta: { title: '本地数据迁移盘点', public: true } },
    { path: '/change-password', name: 'change-password', component: () => import('@/views/ChangePasswordView.vue'), meta: { title: '修改临时密码' } },
    { path: '/quotation/overview', name: 'quotation-overview', component: () => import('@/views/QuotationOverviewView.vue'), meta: { title: '报价情况预览', permission: 'allRecords' } },
    { path: '/quotation', name: 'quotation', component: () => import('@/views/QuotationSystemView.vue'), meta: { title: '我的报价', permission: 'quote' } },
    { path: '/quotation/products', name: 'quotation-products', component: () => import('@/views/JerryModuleView.vue'), props: { mode: 'products' }, meta: { title: '采购', permission: 'purchase' } },
    { path: '/quotation/suppliers', redirect: '/quotation/products' },
    { path: '/quotation/logistics', name: 'quotation-logistics', component: () => import('@/views/LogisticsWorkspaceView.vue'), meta: { title: '物流', permission: 'logistics' } },
    { path: '/quotation/members', name: 'quotation-members', component: () => import('@/views/JerryModuleView.vue'), props: { mode: 'members' }, meta: { title: '财务', permission: 'finance' } },
    { path: '/quotation/my-records', name: 'quotation-my-records', component: () => import('@/views/QuotationRecordsView.vue'), props: { scope: 'mine' }, meta: { title: '我的报价记录', permissions: ['myRecords', 'allRecords'] } },
    { path: '/quotation/records', name: 'quotation-records', component: () => import('@/views/QuotationRecordsView.vue'), props: { scope: 'company' }, meta: { title: '报价记录', permission: 'allRecords' } },
    { path: '/quotation/permissions', name: 'quotation-permissions', component: () => import('@/views/PermissionManagementView.vue'), meta: { title: '权限管理', permission: 'permissions' } },
    { path: '/quotation/migrations', name: 'quotation-migrations', component: () => import('@/views/MigrationManagementView.vue'), meta: { title: '数据迁移', permission: 'permissions' } },
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
