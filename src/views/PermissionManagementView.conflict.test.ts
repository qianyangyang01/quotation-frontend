// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { ApiError } from '@/services/http'
const dependencies = vi.hoisted(() => ({ load: vi.fn(), updateRole: vi.fn() }))
vi.mock('@/data/authStore', async importOriginal => ({
  ...await importOriginal<typeof import('@/data/authStore')>(),
  loadAuthUsers: dependencies.load,
  updateAuthUserRole: dependencies.updateRole,
}))
import View from './PermissionManagementView.vue'
import { authState } from '@/data/authStore'
let app: App
afterEach(() => { app?.unmount(); authState.users = []; document.body.innerHTML = ''; vi.resetAllMocks() })

it('refreshes the rejected account change and shows the other administrators disabled state', async () => {
  const employee = { id: 'emp', name: '测试员工', account: 'EMP01', role: 'employee' as const, status: 'enabled' as const, version: 3, mustChangePassword: false, passwordUpdatedAt: '' }
  authState.users = [employee]
  dependencies.load.mockResolvedValueOnce([employee]).mockImplementationOnce(async () => {
    authState.users = [{ ...employee, status: 'disabled', version: 4 }]
    return authState.users
  })
  dependencies.updateRole.mockRejectedValue(new ApiError('账号资料已更新', 409, 'CONFLICT', 'test'))
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(View); app.mount(host); await nextTick()
  const select = host.querySelector('tbody select') as HTMLSelectElement
  select.value = 'purchase'; select.dispatchEvent(new Event('change', { bubbles: true }))
  await vi.waitFor(() => expect(host.textContent).toContain('账号资料已同步为最新状态'))
  expect(dependencies.load).toHaveBeenCalledTimes(2)
  expect(host.querySelector('tbody tr')?.textContent).toContain('已停用')
  expect((host.querySelector('tbody select') as HTMLSelectElement).value).toBe('employee')
  expect(host.textContent).not.toContain('已将测试员工调整为')
})
