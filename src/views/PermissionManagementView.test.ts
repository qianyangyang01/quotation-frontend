// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import View from './PermissionManagementView.vue'
import { authState, type AuthUser } from '@/data/authStore'

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

it('shows both employee roles, their separate counts and the operations role without permissions', async () => {
  const user = (id: string, role: AuthUser['role']): AuthUser => ({
    id, name: id, account: id, role, status: 'enabled', mustChangePassword: false, passwordUpdatedAt: '',
  })
  authState.users = [user('BUSINESS01', 'employee'), user('OPS01', 'operations_employee')]
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(View); app.mount(host); await nextTick()

  expect(host.querySelector('.summary article b')?.textContent).toBe('6')
  const cards = [...host.querySelectorAll('.role-grid article')]
  expect(cards).toHaveLength(6)
  const business = cards.find(card => card.querySelector('b')?.textContent === '业务-员工')!
  const operations = cards.find(card => card.querySelector('b')?.textContent === '运营-员工')!
  expect(business.querySelector('small')?.textContent).toBe('1 个账号')
  expect(operations.querySelector('small')?.textContent).toBe('1 个账号')
  expect([...business.querySelectorAll('em')].map(tag => tag.textContent)).toEqual(['我的报价', '我的报价记录'])
  expect([...operations.querySelectorAll('em')].map(tag => tag.textContent)).toEqual(['暂无权限'])
  expect(host.querySelectorAll('tbody tr')[1]?.querySelector('.scope-tags')?.textContent).toBe('暂无权限')

  const filter = host.querySelector('.accounts-card>header select') as HTMLSelectElement
  expect([...filter.options].map(option => option.text)).toContain('业务-员工')
  expect([...filter.options].map(option => option.text)).toContain('运营-员工')
  filter.value = 'operations_employee'; filter.dispatchEvent(new Event('change', { bubbles: true })); await nextTick()
  expect(host.querySelectorAll('tbody tr')).toHaveLength(1)
  expect(host.querySelector('tbody tr')?.textContent).toContain('OPS01')
  expect((host.querySelector('tbody select') as HTMLSelectElement).value).toBe('operations_employee')

  ;[...host.querySelectorAll('button')].find(button => button.textContent === '＋ 新增账号')!.click()
  await nextTick()
  const roles = [...(host.querySelector('.add-card select') as HTMLSelectElement).options]
  expect(roles.map(option => option.value)).toContain('operations_employee')
  expect(roles.find(option => option.value === 'employee')?.text).toBe('业务-员工')
})
