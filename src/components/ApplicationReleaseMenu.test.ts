// @vitest-environment happy-dom
import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { authState } from '@/data/authStore'
import { appUpdate, createAppUpdateMonitor } from '@/services/appUpdate'
import { currentApplicationRelease } from '@/services/applicationRelease'
import ApplicationReleaseMenu from './ApplicationReleaseMenu.vue'

vi.mock('@/services/appUpdate', async original => {
  const actual = await original<typeof import('@/services/appUpdate')>()
  return { ...actual, appUpdate: actual.createAppUpdateMonitor('compatible') }
})
let app: App | undefined
beforeEach(() => {
  localStorage.clear()
  Object.assign(appUpdate, createAppUpdateMonitor('compatible', undefined, { releaseBuildId: 'loaded', requiredEpoch: 'notice-v1' }))
  authState.current = { id: 'qa', name: '测试', account: 'QA', role: 'employee', status: 'enabled', mustChangePassword: false, passwordUpdatedAt: '' }
})
afterEach(() => { app?.unmount(); app = undefined; document.body.innerHTML = ''; authState.current = null; localStorage.clear(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
async function mount() {
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(ApplicationReleaseMenu); app.mount(host); await nextTick(); return host
}
describe('release menu protects ongoing work', () => {
  it('announces once without stealing focus, and outside interaction closes it without changing the form', async () => {
    const input = document.createElement('input'); document.body.append(input); input.value = '未保存的报价'; input.focus()
    const host = await mount()
    expect(host.querySelector('.release-popover')).not.toBeNull()
    expect(document.activeElement).toBe(input)
    input.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true })); await nextTick()
    expect(host.querySelector('.release-popover')).toBeNull()
    expect(input.value).toBe('未保存的报价')
    expect(document.activeElement).toBe(input)
    expect(appUpdate.state.updateRequired).toBe(false)
    app!.unmount(); app = undefined
    expect((await mount()).querySelector('.release-popover')).toBeNull()
  })
  it('shows a later ordinary release without gating accepted work and opens details only by click', async () => {
    localStorage.setItem(`milano.release-read:QA:${currentApplicationRelease.version}`, 'yes')
    const host = await mount(), finish = appUpdate.beginOperation()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ schemaVersion: 1, buildId: 'compatible', releaseBuildId: 'next', release: { ...currentApplicationRelease, version: 'next-release', title: '新的简短说明' } }))))
    await appUpdate.check(); await nextTick()
    expect(host.textContent).toContain('新的简短说明')
    expect(host.textContent).toContain('更新后可使用')
    await expect(appUpdate.ensureCurrent()).resolves.toBeUndefined()
    expect(appUpdate.state.activeOperations).toBe(1)
    expect(appUpdate.state.notesOpen).toBe(false)
    host.querySelector<HTMLButtonElement>('.all-notes')!.click(); await nextTick()
    expect(appUpdate.state.notesOpen).toBe(true)
    expect(host.querySelector('.release-popover')).toBeNull()
    finish()
  })
  it('works when local storage is unavailable and keeps Escape focus in the menu only when appropriate', async () => {
    vi.spyOn(localStorage, 'getItem').mockImplementation(() => { throw new Error('unavailable') })
    vi.spyOn(localStorage, 'setItem').mockImplementation(() => { throw new Error('unavailable') })
    const host = await mount()
    host.querySelector<HTMLButtonElement>('.close-release')!.focus()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); await nextTick()
    expect(host.querySelector('.release-popover')).toBeNull()
    expect(document.activeElement).toBe(host.querySelector('.release-tool'))
    host.querySelector<HTMLButtonElement>('.release-tool')!.click(); await nextTick()
    expect(host.querySelector('.release-popover')).not.toBeNull()
  })
})
