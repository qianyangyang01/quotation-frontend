// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'

const dependencies = vi.hoisted(() => ({ post: vi.fn(), schedulePrefetch: vi.fn(), mountNotifications: vi.fn() }))
vi.mock('@/services/http', () => ({ api: { post: dependencies.post }, request: vi.fn(), setRequestAccount: vi.fn(), resetCsrf: vi.fn() }))
vi.mock('@/services/financeSettings', () => ({ clearFinanceSettingsCache: vi.fn() }))
vi.mock('@/data/publishedLogisticsRepository', () => ({ clearPublishedLogisticsCache: vi.fn() }))
vi.mock('@/router/routeViews', () => ({ prefetchRouteView: vi.fn(), scheduleRouteViewPrefetch: dependencies.schedulePrefetch }))
vi.mock('@/components/quotation/ReviewNotificationBell.vue', () => ({ default: { setup() { dependencies.mountNotifications(); return () => null } } }))

import { authState, roleDefinitions, type RoleKey } from '@/data/authStore'
import AppTopbar from './AppTopbar.vue'
import NoAccessView from '@/views/NoAccessView.vue'

let app: App | undefined
beforeEach(() => {
  vi.clearAllMocks()
  dependencies.schedulePrefetch.mockReturnValue(() => undefined)
})
afterEach(() => {
  app?.unmount()
  document.body.innerHTML = ''
  authState.current = null
  authState.permissions = []
})

async function mount(role: RoleKey) {
  authState.current = { id: 'user-1', name: '测试账号', account: 'TEST01', role, status: 'enabled', mustChangePassword: false, passwordUpdatedAt: '' }
  authState.permissions = [...roleDefinitions.find(item => item.key === role)!.permissions]
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }] })
  await router.push(role === 'operations_employee' ? '/quotation/no-access' : '/quotation')
  await router.isReady()
  const host = document.createElement('div')
  document.body.append(host)
  app = createApp({ render: () => h('div', [h(AppTopbar), ...(role === 'operations_employee' ? [h(NoAccessView)] : [])]) }).use(router)
  app.mount(host)
  await nextTick()
  return { host, router }
}

describe('role-aware app navigation', () => {
  it('shows the empty-permission landing without navigation, notifications or prefetch, and allows logout', async () => {
    const { host, router } = await mount('operations_employee')
    expect(host.querySelector('h1')?.textContent).toBe('暂无业务权限')
    expect(host.textContent).toContain('运营-员工')
    expect(host.querySelector('.app-brand')?.getAttribute('href')).toBe('/quotation/no-access')
    expect(host.querySelector('nav')).toBeNull()
    expect(dependencies.mountNotifications).not.toHaveBeenCalled()
    expect(dependencies.schedulePrefetch).not.toHaveBeenCalled()
    expect(dependencies.post).not.toHaveBeenCalled()

    host.querySelector<HTMLButtonElement>('.app-user > button')!.click()
    await nextTick()
    expect(host.querySelector('.account-menu a')).toBeNull()
    host.querySelector<HTMLButtonElement>('.logout')!.click()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect(dependencies.post).toHaveBeenCalledExactlyOnceWith('/auth/logout')
    expect(authState.current).toBeNull()
    expect(authState.permissions).toEqual([])
  })

  it('keeps business employees quotation navigation and notifications under the new display name', async () => {
    const { host } = await mount('employee')
    expect(host.textContent).toContain('业务-员工')
    expect([...host.querySelectorAll('nav a')].map(link => link.textContent)).toEqual(['我的报价', '我的报价记录'])
    expect(host.querySelector('.app-brand')?.getAttribute('href')).toBe('/quotation')
    expect(dependencies.mountNotifications).toHaveBeenCalledOnce()
    expect(dependencies.schedulePrefetch).toHaveBeenCalledExactlyOnceWith(['/quotation/my-records'])
  })
})
