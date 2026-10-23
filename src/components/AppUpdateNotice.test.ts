// @vitest-environment happy-dom
import { createApp, nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AppUpdateNotice from './AppUpdateNotice.vue'
import { appUpdate, refreshUpdatedApplication } from '@/services/appUpdate'

vi.mock('@/services/appUpdate', async importOriginal => {
  const actual = await importOriginal<typeof import('@/services/appUpdate')>()
  return { ...actual, appUpdate: actual.createAppUpdateMonitor('current'), refreshUpdatedApplication: vi.fn() }
})

let unmount: (() => void) | undefined
afterEach(() => { unmount?.(); document.body.innerHTML = ''; vi.unstubAllGlobals(); vi.clearAllMocks() })

describe('global application update notice', () => {
  it('shows a recoverable network problem without clearing the surrounding form', async () => {
    const host = document.createElement('div'); document.body.append(host)
    const input = document.createElement('input'); input.value = '正在填写的报价'; document.body.append(input)
    const app = createApp(AppUpdateNotice); app.mount(host); unmount = () => app.unmount()
    expect(host.textContent).toBe('')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    await appUpdate.check(); await nextTick()
    expect(host.textContent).toContain('暂时无法确认系统版本')
    expect(input.value).toBe('正在填写的报价')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ schemaVersion: 1, buildId: 'current' }))))
    await appUpdate.check(); await nextTick()
    expect(host.textContent).toBe('')
  })

  it('requires a deliberate refresh, preserves the page and disables refresh while work is pending', async () => {
    const host = document.createElement('div'); document.body.append(host)
    const app = createApp(AppUpdateNotice); app.mount(host); unmount = () => app.unmount()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ schemaVersion: 1, buildId: 'next' }))))
    await appUpdate.check(); await nextTick()
    expect(host.textContent).toContain('系统已更新')
    expect(host.textContent).toContain('历史报价不受影响')
    expect(refreshUpdatedApplication).not.toHaveBeenCalled()
    const button = host.querySelector('button')!
    const finish = appUpdate.beginOperation(); await nextTick()
    expect(button.disabled).toBe(true)
    expect(host.textContent).toContain('正在提交或上传')
    button.click()
    expect(refreshUpdatedApplication).not.toHaveBeenCalled()
    finish(); await nextTick(); button.click()
    expect(refreshUpdatedApplication).toHaveBeenCalledTimes(1)
  })

  it('explains an exchange-rate update separately from an application release', async () => {
    const host = document.createElement('div'); document.body.append(host)
    const app = createApp(AppUpdateNotice); app.mount(host); unmount = () => app.unmount()
    appUpdate.requireUpdate('exchange-rate'); await nextTick()
    expect(host.textContent).toContain('财务汇率已更新')
    expect(host.textContent).toContain('保留未保存内容')
  })
})
