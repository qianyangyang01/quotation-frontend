// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const dependencies = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), loadedViews: vi.fn() }))
vi.mock('@/services/http', () => ({ api: { get: dependencies.get, post: dependencies.post }, request: vi.fn(), setRequestAccount: vi.fn(), resetCsrf: vi.fn() }))
vi.mock('@/services/financeSettings', () => ({ clearFinanceSettingsCache: vi.fn() }))
vi.mock('@/data/publishedLogisticsRepository', () => ({ clearPublishedLogisticsCache: vi.fn() }))
vi.mock('@/router/routeViews', () => ({ routeView: (path: string) => () => {
  dependencies.loadedViews(path)
  return Promise.resolve({ default: { render: () => null } })
} }))

import { authState, changeCurrentPassword, defaultHomeForRole } from '@/data/authStore'
import router from './index'

beforeEach(async () => {
  authState.current = null
  authState.permissions = []
  authState.initialized = true
  await router.replace('/login')
  vi.clearAllMocks()
  authState.current = { id: 'operations-1', name: '运营员工', account: 'OPS01', role: 'operations_employee', status: 'enabled', mustChangePassword: false, passwordUpdatedAt: '' }
})
afterEach(() => { authState.current = null; authState.permissions = [] })

describe('operations employee route access', () => {
  it('sends every business route and fallback entry to the safe landing without loading business views', async () => {
    const routes = [
      '/', '/login', '/quotation/overview', '/quotation', '/quotation/batch-single',
      '/quotation/withdrawn-edit?record=sample', '/quotation/products', '/quotation/suppliers',
      '/quotation/logistics', '/quotation/members', '/quotation/my-records',
      '/quotation/records', '/quotation/permissions', '/quotation/history', '/missing',
    ]
    for (const path of routes) {
      await router.push(path)
      expect(router.currentRoute.value.path, path).toBe('/quotation/no-access')
    }
    expect(dependencies.loadedViews.mock.calls.every(([path]) => path === '/quotation/no-access')).toBe(true)
    expect(dependencies.get).not.toHaveBeenCalled()
    expect(dependencies.post).not.toHaveBeenCalled()
  })

  it('requires login before displaying the no-access landing', async () => {
    authState.current = null
    await router.push('/quotation/no-access')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/quotation/no-access')
  })

  it('requires a temporary password change and then returns to the no-access landing', async () => {
    authState.current!.mustChangePassword = true
    await router.push('/quotation/no-access')
    expect(router.currentRoute.value.path).toBe('/change-password')
    await router.push('/quotation/permissions')
    expect(router.currentRoute.value.path).toBe('/change-password')

    dependencies.post.mockResolvedValue(undefined)
    await changeCurrentPassword('TemporaryPass123', 'UpdatedPass456')
    await router.replace(defaultHomeForRole())
    expect(dependencies.post).toHaveBeenCalledExactlyOnceWith('/auth/change-password', { currentPassword: 'TemporaryPass123', newPassword: 'UpdatedPass456' })
    expect(authState.current!.mustChangePassword).toBe(false)
    expect(router.currentRoute.value.path).toBe('/quotation/no-access')
    await router.push('/change-password')
    expect(router.currentRoute.value.path).toBe('/quotation/no-access')
  })
})
