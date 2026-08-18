import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    { path: '/', redirect: '/quotation' },
    { path: '/quotation', name: 'quotation', component: () => import('@/views/QuotationSystemView.vue'), meta: { title: '业务报价' } },
    { path: '/quotation/products', name: 'quotation-products', component: () => import('@/views/JerryModuleView.vue'), props: { mode: 'products' }, meta: { title: '采购资料' } },
    { path: '/quotation/suppliers', name: 'quotation-suppliers', component: () => import('@/views/JerryModuleView.vue'), props: { mode: 'suppliers' }, meta: { title: '供应商管理' } },
    { path: '/quotation/logistics', name: 'quotation-logistics', component: () => import('@/views/SumaoLogisticsReplicaView.vue'), meta: { title: '物流规则' } },
    { path: '/quotation/members', name: 'quotation-members', component: () => import('@/views/JerryModuleView.vue'), props: { mode: 'members' }, meta: { title: '财务设置' } },
    { path: '/quotation/my-records', name: 'quotation-my-records', component: () => import('@/views/QuotationRecordsView.vue'), props: { scope: 'mine' }, meta: { title: '我的报价记录' } },
    { path: '/quotation/records', name: 'quotation-records', component: () => import('@/views/QuotationRecordsView.vue'), props: { scope: 'company' }, meta: { title: '公司报价记录' } },
    { path: '/quotation/history', redirect: '/quotation/records' },
    { path: '/:pathMatch(.*)*', redirect: '/quotation' },
  ],
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title || '报价系统')} · 米莱诺报价`
})

export default router
