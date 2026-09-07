// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from './PermissionManagementView.vue'
import { authState } from '@/data/authStore'

vi.mock('@/data/authStore', async importOriginal => ({
  ...await importOriginal<typeof import('@/data/authStore')>(),
  loadAuthUsers: vi.fn().mockResolvedValue([]),
}))
let app: App
afterEach(() => { app?.unmount(); authState.users = []; document.body.innerHTML = '' })
it('mounts with ten accounts, pages the remainder and resets after filtering', async () => {
  authState.users = Array.from({ length: 23 }, (_, index) => ({
    id: String(index), name: '员工' + index, account: 'USER' + index, role: 'employee',
    status: 'enabled', mustChangePassword: false, passwordUpdatedAt: '',
  }))
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(View); app.mount(host); await nextTick()
  const rows = () => host.querySelectorAll('tbody tr')
  const button = (label: string) => [...host.querySelectorAll('button')].find(item => item.textContent === label)!
  expect(rows()).toHaveLength(10)
  button('末页').click(); await nextTick()
  expect(rows()).toHaveLength(3)
  expect(host.querySelector('.accounts-pagination')?.textContent).toContain('21–23')
  const input = host.querySelector('[placeholder="搜索姓名或账号"]') as HTMLInputElement
  input.value = 'USER22'; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  expect(rows()).toHaveLength(1)
  expect(host.querySelector('.accounts-pagination')?.textContent).toContain('1 / 1')
  input.value = ''; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
  const select = host.querySelector('[aria-label="账号每页条数"]') as HTMLSelectElement
  select.value = '30'; select.dispatchEvent(new Event('change', { bubbles: true })); await nextTick()
  expect(rows()).toHaveLength(23)
})
