import { beforeEach, describe, expect, it, vi } from 'vitest'
const dependencies = vi.hoisted(() => ({ post: vi.fn(), resetCsrf: vi.fn(), hydrateFinanceSettings: vi.fn(), clearFinanceSettingsCache: vi.fn(), clearPublishedLogisticsCache: vi.fn() }))
vi.mock('@/services/http', () => ({ api: { post: dependencies.post }, setRequestAccount: vi.fn(), resetCsrf: dependencies.resetCsrf }))
vi.mock('@/services/financeSettings', () => ({ hydrateFinanceSettings: dependencies.hydrateFinanceSettings, clearFinanceSettingsCache: dependencies.clearFinanceSettingsCache }))
vi.mock('@/data/publishedLogisticsRepository', () => ({ clearPublishedLogisticsCache: dependencies.clearPublishedLogisticsCache }))
import { authState, login, defaultHomeForRole, type RoleKey } from './authStore'

describe('login does not depend on unrelated workspace data', () => {
  beforeEach(() => { vi.clearAllMocks(); authState.current = null; authState.permissions = []; authState.initialized = false })
  it.each<[RoleKey, string]>([['super_admin', '/quotation/overview'], ['finance', '/quotation/overview'], ['employee', '/quotation'], ['purchase', '/quotation/products'], ['logistics', '/quotation/logistics']])(
    '%s reaches its homepage without waiting for finance', async (role, home) => {
      dependencies.hydrateFinanceSettings.mockReturnValue(new Promise(() => {}))
      dependencies.post.mockResolvedValue({ id: 'user-1', name: 'Test', account: 'TEST', role, permissions: [], mustChangePassword: false })
      await expect(login('test', 'fixture')).resolves.toMatchObject({ ok: true, user: { role } })
      expect(dependencies.post).toHaveBeenCalledWith('/auth/login', { account: 'TEST', password: 'fixture' })
      expect(dependencies.hydrateFinanceSettings).not.toHaveBeenCalled()
      expect(dependencies.clearFinanceSettingsCache).toHaveBeenCalledOnce()
      expect(defaultHomeForRole()).toBe(home)
    },
  )
  it('does not invalidate current settings or establish a session on failed authentication', async () => {
    dependencies.post.mockRejectedValue(new Error('账号或密码错误'))
    await expect(login('test', 'wrong')).resolves.toEqual({ ok: false, message: '账号或密码错误' })
    expect(authState.current).toBeNull()
    expect(dependencies.clearFinanceSettingsCache).not.toHaveBeenCalled()
  })
})
