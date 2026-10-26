// @vitest-environment happy-dom
import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AppUpdateNotice from './AppUpdateNotice.vue'
import { appUpdate, createAppUpdateMonitor, refreshUpdatedApplication } from '@/services/appUpdate'

vi.mock('@/services/appUpdate', async importOriginal => {
  const actual = await importOriginal<typeof import('@/services/appUpdate')>()
  return { ...actual, appUpdate: actual.createAppUpdateMonitor('current'), refreshUpdatedApplication: vi.fn() }
})

let unmount: (() => void) | undefined
beforeEach(() => { Object.assign(appUpdate, createAppUpdateMonitor('current')) })
afterEach(() => { unmount?.(); unmount = undefined; document.body.innerHTML = ''; vi.useRealTimers(); vi.unstubAllGlobals(); vi.clearAllMocks() })

describe('global application update notice', () => {
  it('keeps repeated network failures and recovery silent without clearing the surrounding form', async () => {
    const host = document.createElement('div'); document.body.append(host)
    const input = document.createElement('input'); input.value = '正在填写的报价'; document.body.append(input)
    const app = createApp(AppUpdateNotice); app.mount(host); unmount = () => app.unmount()
    expect(host.textContent).toBe('')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    for (let attempt = 0; attempt < 3; attempt++) {
      await appUpdate.check(); await nextTick()
      expect(appUpdate.state.unavailable).toBe(true)
      expect(host.querySelector('[role="alert"]')).toBeNull()
      expect(host.textContent).toBe('')
    }
    expect(input.value).toBe('正在填写的报价')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ schemaVersion: 1, buildId: 'current' }))))
    await appUpdate.check(); await nextTick()
    expect(host.textContent).toBe('')
  })

  it('silently retries a timed-out background check and shows only a confirmed release', async () => {
    vi.useFakeTimers()
    const fetcher = vi.fn().mockImplementationOnce(() => new Promise<Response>(() => undefined))
      .mockImplementationOnce(() => Promise.resolve(new Response(JSON.stringify({ schemaVersion: 1, buildId: 'current' }))))
      .mockImplementationOnce(() => Promise.resolve(new Response(JSON.stringify({ schemaVersion: 1, buildId: 'next' }))))
      .mockRejectedValue(new Error('offline'))
    vi.stubGlobal('fetch', fetcher)
    const host = document.createElement('div'); document.body.append(host)
    const app = createApp(AppUpdateNotice); app.mount(host); unmount = () => app.unmount()
    const stop = appUpdate.start()
    try {
      await vi.advanceTimersByTimeAsync(2_001); await nextTick()
      expect(appUpdate.state.unavailable).toBe(true)
      expect(host.textContent).toBe('')
      await vi.advanceTimersByTimeAsync(57_999); await nextTick()
      expect(appUpdate.state.unavailable).toBe(false)
      expect(host.textContent).toBe('')
      await vi.advanceTimersByTimeAsync(60_000); await nextTick()
      expect(fetcher).toHaveBeenCalledTimes(3)
      expect(host.textContent).toContain('系统已更新')
      await appUpdate.check(); await nextTick()
      expect(appUpdate.state.unavailable).toBe(true)
      expect(host.textContent).toContain('系统已更新')
      expect(refreshUpdatedApplication).not.toHaveBeenCalled()
    } finally { stop() }
  })

  it('requires two deliberate actions and rechecks pending work before confirming refresh', async () => {
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
    finish(); await nextTick(); button.click(); await nextTick()
    expect(refreshUpdatedApplication).not.toHaveBeenCalled()
    expect(host.textContent).toContain('尚未保存的输入将被清除')
    const confirmButton = Array.from(host.querySelectorAll('button')).find(button => button.textContent === '已保留内容，确认刷新')!
    const finishDuringConfirmation = appUpdate.beginOperation()
    confirmButton.click() // The handler also guards before Vue renders disabled.
    expect(refreshUpdatedApplication).not.toHaveBeenCalled()
    await nextTick()
    expect(confirmButton.disabled).toBe(true)
    expect(host.textContent).toContain('正在提交或上传')
    finishDuringConfirmation(); await nextTick(); confirmButton.click()
    expect(refreshUpdatedApplication).toHaveBeenCalledTimes(1)
    expect(refreshUpdatedApplication).toHaveBeenCalledWith(true)
  })

  it('keeps inline confirmation cancellable by button and keyboard without blocking form editing', async () => {
    const nativeConfirm = vi.fn()
    vi.stubGlobal('confirm', nativeConfirm)
    const host = document.createElement('div'); document.body.append(host)
    const input = document.createElement('input'); input.value = '正在填写的报价'; document.body.append(input)
    const app = createApp(AppUpdateNotice); app.mount(host); unmount = () => app.unmount()
    appUpdate.requireUpdate(); await nextTick()
    const button = (label: string) => Array.from(host.querySelectorAll('button')).find(button => button.textContent === label)!
    button('保留内容后刷新').click(); await nextTick()
    expect(document.activeElement).toBe(button('取消'))
    expect(host.querySelector('dialog')).toBeNull()
    input.focus(); input.value = '已复制保留的报价'
    expect(document.activeElement).toBe(input)
    button('取消').click(); await nextTick()
    expect(document.activeElement).toBe(button('保留内容后刷新'))
    expect(host.querySelector('[aria-label="确认刷新页面"]')).toBeNull()
    button('保留内容后刷新').click(); await nextTick()
    button('取消').dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); await nextTick()
    expect(document.activeElement).toBe(button('保留内容后刷新'))
    expect(input.value).toBe('已复制保留的报价')
    expect(refreshUpdatedApplication).not.toHaveBeenCalled()
    expect(nativeConfirm).not.toHaveBeenCalled()
  })

  it('explains an exchange-rate update separately from an application release', async () => {
    const host = document.createElement('div'); document.body.append(host)
    const app = createApp(AppUpdateNotice); app.mount(host); unmount = () => app.unmount()
    appUpdate.requireUpdate('exchange-rate'); await nextTick()
    expect(host.textContent).toContain('财务汇率已更新')
    expect(host.textContent).toContain('保留未保存内容')
  })
})
