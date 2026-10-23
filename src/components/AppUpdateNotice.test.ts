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
