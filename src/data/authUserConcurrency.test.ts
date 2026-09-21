import { beforeEach, describe, expect, it, vi } from 'vitest'
const dependencies = vi.hoisted(() => ({ get: vi.fn(), patch: vi.fn() }))
vi.mock('@/services/http', () => ({ api: dependencies, setRequestAccount: vi.fn(), resetCsrf: vi.fn() }))
vi.mock('@/services/financeSettings', () => ({ hydrateFinanceSettings: vi.fn(), clearFinanceSettingsCache: vi.fn() }))
vi.mock('@/data/publishedLogisticsRepository', () => ({ clearPublishedLogisticsCache: vi.fn() }))
import { authState, loadAuthUsers, updateAuthUserRole, updateAuthUserStatus, type AuthUser } from './authStore'
const employee: AuthUser = { id: 'employee', account: 'EMP01', name: '测试员工', role: 'employee', status: 'enabled', version: 3, mustChangePassword: false, passwordUpdatedAt: '' }
beforeEach(() => { vi.resetAllMocks(); authState.users = [{ ...employee }]; authState.current = null })

describe('administrator account version consistency', () => {
  it('sends the displayed version and uses the returned version for the next change', async () => {
    dependencies.patch.mockResolvedValueOnce({ ...employee, role: 'purchase', version: 4 })
    await updateAuthUserRole(employee.id, 'purchase')
    expect(dependencies.patch).toHaveBeenLastCalledWith('/users/employee', { role: 'purchase', status: 'enabled', version: 3 })
    dependencies.patch.mockResolvedValueOnce({ ...employee, role: 'purchase', status: 'disabled', version: 5 })
    await updateAuthUserStatus(employee.id, 'disabled')
    expect(dependencies.patch).toHaveBeenLastCalledWith('/users/employee', { role: 'purchase', status: 'disabled', version: 4 })
    expect(authState.users[0]?.version).toBe(5)
  })

  it('does not mutate local permissions when a stale write is rejected', async () => {
    dependencies.patch.mockRejectedValue(new Error('账号资料已更新'))
    await expect(updateAuthUserRole(employee.id, 'purchase')).rejects.toThrow('账号资料已更新')
    expect(authState.users[0]).toEqual(employee)
  })

  it('does not let an older in-flight list replace a successful account change', async () => {
    let resolveList!: (users: AuthUser[]) => void
    dependencies.get.mockReturnValue(new Promise<AuthUser[]>(resolve => { resolveList = resolve }))
    const pending = loadAuthUsers()
    dependencies.patch.mockResolvedValue({ ...employee, status: 'disabled', version: 4 })
    await updateAuthUserStatus(employee.id, 'disabled')
    resolveList([{ ...employee }]); await pending
    expect(authState.users[0]?.status).toBe('disabled')
    expect(authState.users[0]?.version).toBe(4)
  })

  it('does not let a late update response overwrite a newer list snapshot', async () => {
    let resolveUpdate!: (user: AuthUser) => void
    dependencies.patch.mockReturnValue(new Promise<AuthUser>(resolve => { resolveUpdate = resolve }))
    const pending = updateAuthUserRole(employee.id, 'purchase')
    dependencies.get.mockResolvedValue([{ ...employee, role: 'logistics', status: 'disabled', version: 5 }])
    await loadAuthUsers()
    resolveUpdate({ ...employee, role: 'purchase', version: 4 }); await pending
    expect(authState.users[0]?.role).toBe('logistics')
    expect(authState.users[0]?.status).toBe('disabled')
  })

  it('requires a fresh account list if the row has no version', async () => {
    delete authState.users[0]!.version
    await expect(updateAuthUserRole(employee.id, 'purchase')).rejects.toThrow('重新加载')
    expect(dependencies.patch).not.toHaveBeenCalled()
  })
})
