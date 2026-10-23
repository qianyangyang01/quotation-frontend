// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { appUpdate, createAppUpdateMonitor, refreshUpdatedApplication, requiresCurrentClient } from './appUpdate'

const response = (buildId = 'current') => new Response(JSON.stringify({ schemaVersion: 1, buildId }))
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('application update monitor', () => {
  it('keeps a finance-version refresh requirement even if the application build is unchanged', async () => {
    const monitor = createAppUpdateMonitor('current', vi.fn().mockResolvedValue(response()))
    monitor.requireUpdate('exchange-rate')
    await monitor.check()
    expect(monitor.state.reason).toBe('exchange-rate')
    await expect(monitor.ensureCurrent()).rejects.toMatchObject({ code: 'CLIENT_UPDATE_REQUIRED' })
  })
  it('compares against the code build, including a deployment before the first poll', async () => {
    const fetcher = vi.fn().mockResolvedValue(response('new'))
    const monitor = createAppUpdateMonitor('current', fetcher)
    await expect(monitor.ensureCurrent()).rejects.toMatchObject({ code: 'CLIENT_UPDATE_REQUIRED' })
    expect(monitor.state.updateRequired).toBe(true)
    expect(fetcher).toHaveBeenCalledWith(expect.stringContaining('/app-version.json?_='), expect.objectContaining({ cache: 'no-store' }))
  })

  it('checks every new submission and latches changes even when the server rolls back', async () => {
    const fetcher = vi.fn().mockImplementation(() => Promise.resolve(response()))
    const monitor = createAppUpdateMonitor('current', fetcher)
    await monitor.ensureCurrent()
    await monitor.ensureCurrent()
    expect(fetcher).toHaveBeenCalledTimes(2)
    fetcher.mockResolvedValueOnce(response('new'))
    await monitor.check()
    await monitor.check()
    expect(monitor.state.updateRequired).toBe(true)
  })

  it.each([
    () => Promise.reject(new Error('offline')),
    () => Promise.resolve(new Response('<html>old release</html>')),
    () => Promise.resolve(new Response('{}')),
    () => Promise.resolve(new Response(JSON.stringify({ schemaVersion: 2, buildId: 'new' }))),
    () => Promise.resolve(new Response('missing', { status: 404 })),
  ])('does not mistake an invalid/unavailable manifest for a confirmed update', async fetcher => {
    const monitor = createAppUpdateMonitor('current', vi.fn(fetcher))
    await expect(monitor.ensureCurrent()).rejects.toMatchObject({ code: 'CLIENT_VERSION_UNAVAILABLE' })
    expect(monitor.state.updateRequired).toBe(false)
    expect(monitor.state.checking).toBe(false)
  })

  it('recovers after a failed or synchronous-throwing check', async () => {
    const fetcher = vi.fn().mockImplementationOnce(() => { throw new Error('network') }).mockImplementation(() => Promise.resolve(response()))
    const monitor = createAppUpdateMonitor('current', fetcher)
    await expect(monitor.ensureCurrent()).rejects.toThrow('暂时无法')
    await monitor.ensureCurrent()
    expect(monitor.state.unavailable).toBe(false)
  })

  it('times out a stalled check and permits retry', async () => {
    vi.useFakeTimers()
    const fetcher = vi.fn().mockImplementationOnce((_url, init: RequestInit) => new Promise((_resolve, reject) => init.signal!.addEventListener('abort', () => reject(new Error('aborted')))))
      .mockImplementation(() => Promise.resolve(response()))
    const monitor = createAppUpdateMonitor('current', fetcher)
    const pending = monitor.ensureCurrent().catch(error => error)
    await vi.advanceTimersByTimeAsync(8_001)
    expect(await pending).toMatchObject({ code: 'CLIENT_VERSION_UNAVAILABLE' })
    await monitor.ensureCurrent()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('shares a check without letting a cancelled caller cancel another submission', async () => {
    let finish!: (value: Response) => void
    const fetcher = vi.fn(() => new Promise<Response>(resolve => { finish = resolve }))
    const monitor = createAppUpdateMonitor('current', fetcher)
    const controller = new AbortController()
    const first = monitor.ensureCurrent(controller.signal).catch(error => error)
    const second = monitor.ensureCurrent()
    await Promise.resolve()
    controller.abort(new Error('cancelled'))
    expect(await first).toMatchObject({ message: 'cancelled' })
    finish(response())
    await second
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('pauses hidden-page polling and checks again on focus/online, with complete cleanup', async () => {
    vi.useFakeTimers()
    const fetcher = vi.fn().mockImplementation(() => Promise.resolve(response()))
    const monitor = createAppUpdateMonitor('current', fetcher)
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    const stop = monitor.start()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(fetcher).toHaveBeenCalledTimes(1)
    visibility.mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    window.dispatchEvent(new Event('focus'))
    await vi.advanceTimersByTimeAsync(0)
    expect(fetcher).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(5_001)
    window.dispatchEvent(new Event('online'))
    await vi.advanceTimersByTimeAsync(0)
    expect(fetcher).toHaveBeenCalledTimes(3)
    stop()
    await vi.advanceTimersByTimeAsync(60_000)
    window.dispatchEvent(new Event('focus'))
    expect(fetcher).toHaveBeenCalledTimes(3)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('disables checks for development and counts overlapping operations exactly once', async () => {
    const fetcher = vi.fn()
    const monitor = createAppUpdateMonitor('', fetcher)
    await monitor.ensureCurrent()
    monitor.start()()
    expect(fetcher).not.toHaveBeenCalled()
    const endFirst = monitor.beginOperation(), endSecond = monitor.beginOperation()
    expect(monitor.state.activeOperations).toBe(2)
    endFirst(); endFirst(); endSecond()
    expect(monitor.state.activeOperations).toBe(0)
  })
})

describe('linked module update policy', () => {
  it.each([
    ['/quotations', 'POST'], ['/quotations/id/resubmit', 'POST'], ['/quotations/fob/preview', 'POST'],
    ['/quotations/id/fob-corrections/submit', 'POST'], ['/batch-single-quotations', 'POST'],
    ['/batch-single-quotations/id/review', 'POST'], ['/purchase-products/SKU/maintenance', 'POST'],
    ['/purchase-imports/jobs', 'POST'], ['/purchase-imports/jobs/id/confirm', 'POST'],
    ['/logistics/rebuild/datasets/id/uploads', 'POST'], ['/logistics/providers/id/versions/publish-batch', 'POST'],
    ['/logistics/rebuild/datasets/id/activate', 'POST'], ['/finance-settings/exchange-rate', 'PUT'],
    ['/users/id', 'PATCH'], ['/quotation-templates/id', 'DELETE'],
  ])('checks new business action %s %s', (path, method) => expect(requiresCurrentClient(path!, method!)).toBe(true))

  it.each([
    ['/quotations/id', 'GET'], ['/auth/logout', 'POST'], ['/auth/login', 'POST'], ['/auth/change-password', 'POST'],
    ['/quotation-drafts/mine/state', 'PUT'], ['/quotation-drafts/mine/state', 'DELETE'],
    ['/logistics/rebuild/uploads/id/files/0/chunks/1', 'POST'], ['/logistics/rebuild/uploads/id/complete', 'POST'],
    ['/purchase-imports/jobs/id/image-parts?partNumber=1', 'POST'], ['/purchase-imports/jobs/id/cancel', 'POST'],
  ])('preserves recovery/read/accepted-upload action %s %s', (path, method) => expect(requiresCurrentClient(path!, method!)).toBe(false))
})

describe('deliberate page refresh', () => {
  it('preserves the route and existing parameters, and never reloads without confirmation', () => {
    window.history.replaceState(null, '', '/quotation?draft=mine#sheet')
    const navigate = vi.spyOn(window.location, 'assign').mockImplementation(() => undefined)
    const confirm = vi.fn().mockReturnValue(false)
    vi.stubGlobal('confirm', confirm)
    refreshUpdatedApplication()
    expect(navigate).not.toHaveBeenCalled()
    confirm.mockReturnValue(true)
    refreshUpdatedApplication()
    const url = new URL(navigate.mock.calls[0]![0] as string)
    expect(url.pathname).toBe('/quotation')
    expect(url.searchParams.get('draft')).toBe('mine')
    expect(url.searchParams.get('_appUpdate')).toBeTruthy()
    expect(url.hash).toBe('#sheet')
  })

  it('refuses refresh while a request is pending and rechecks after confirmation', () => {
    const navigate = vi.spyOn(window.location, 'assign').mockImplementation(() => undefined)
    const confirm = vi.fn().mockReturnValue(true)
    vi.stubGlobal('confirm', confirm)
    const end = appUpdate.beginOperation()
    refreshUpdatedApplication()
    expect(confirm).not.toHaveBeenCalled()
    end()
    let finish!: () => void
    confirm.mockImplementation(() => { finish = appUpdate.beginOperation(); return true })
    refreshUpdatedApplication()
    expect(navigate).not.toHaveBeenCalled()
    finish()
  })
})
