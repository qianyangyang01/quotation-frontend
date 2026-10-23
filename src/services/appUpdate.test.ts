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

  it('shares a recent result and latches changes even when the server rolls back', async () => {
    vi.useFakeTimers()
    const fetcher = vi.fn().mockImplementation(() => Promise.resolve(response()))
    const monitor = createAppUpdateMonitor('current', fetcher)
    await monitor.ensureCurrent()
    await monitor.ensureCurrent()
    expect(fetcher).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(10_001)
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
    await expect(monitor.ensureCurrent()).resolves.toBeUndefined()
    expect(monitor.state.updateRequired).toBe(false)
    expect(monitor.state.unavailable).toBe(true)
    expect(monitor.state.checking).toBe(false)
  })

  it('recovers after a failed or synchronous-throwing check', async () => {
    const fetcher = vi.fn().mockImplementationOnce(() => { throw new Error('network') }).mockImplementation(() => Promise.resolve(response()))
    const monitor = createAppUpdateMonitor('current', fetcher)
    await expect(monitor.ensureCurrent()).resolves.toBeUndefined()
    await monitor.check()
    expect(monitor.state.unavailable).toBe(false)
  })

  it.each(['headers', 'body'])('bounds a stalled %s even if abort is ignored and discards a late mismatch', async stage => {
    vi.useFakeTimers()
    let finish!: (value: unknown) => void
    const stalled = new Promise(resolve => { finish = resolve })
    const fetcher = vi.fn().mockImplementationOnce(() => stage === 'headers' ? stalled : Promise.resolve({ ok: true, json: () => stalled }))
      .mockImplementation(() => Promise.resolve(response()))
    const monitor = createAppUpdateMonitor('current', fetcher)
    const pending = monitor.ensureCurrent()
    await vi.advanceTimersByTimeAsync(2_001)
    await expect(pending).resolves.toBeUndefined()
    expect(monitor.state.checking).toBe(false)
    expect(monitor.state.unavailable).toBe(true)
    await monitor.check()
    finish(stage === 'headers' ? response('new') : { schemaVersion: 1, buildId: 'new' })
    await vi.advanceTimersByTimeAsync(0)
    expect(monitor.state.updateRequired).toBe(false)
    expect(monitor.state.unavailable).toBe(false)
    await monitor.ensureCurrent()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('backs off after metadata failure during concurrent and sequential business operations, then detects recovery', async () => {
    vi.useFakeTimers()
    const fetcher = vi.fn().mockRejectedValueOnce(new Error('offline')).mockImplementation(() => Promise.resolve(response('new')))
    const monitor = createAppUpdateMonitor('current', fetcher)
    await Promise.all(Array.from({ length: 100 }, () => monitor.ensureCurrent()))
    for (let i = 0; i < 100; i++) await monitor.ensureCurrent()
    expect(fetcher).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(30_001)
    await expect(monitor.ensureCurrent()).rejects.toMatchObject({ code: 'CLIENT_UPDATE_REQUIRED' })
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(monitor.state.unavailable).toBe(false)
  })

  it('blocks immediately when a confirmed update arrives inside the success TTL', async () => {
    const monitor = createAppUpdateMonitor('current', vi.fn().mockResolvedValue(response()))
    await monitor.ensureCurrent()
    monitor.requireUpdate()
    await expect(monitor.ensureCurrent()).rejects.toMatchObject({ code: 'CLIENT_UPDATE_REQUIRED' })
  })

  it('waits for a running background check even inside the success TTL', async () => {
    let finish!: (value: Response) => void
    const fetcher = vi.fn().mockResolvedValueOnce(response()).mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve }))
    const monitor = createAppUpdateMonitor('current', fetcher)
    await monitor.ensureCurrent()
    const check = monitor.check()
    const result = monitor.ensureCurrent().catch(error => error)
    let settled = false
    void result.then(() => { settled = true })
    await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
    expect(settled).toBe(false)
    finish(response('new'))
    await check
    expect(await result).toMatchObject({ code: 'CLIENT_UPDATE_REQUIRED' })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('shares a check without letting a cancelled caller cancel another submission', async () => {
    let finish!: (value: Response) => void
    const fetcher = vi.fn(() => new Promise<Response>(resolve => { finish = resolve }))
    const monitor = createAppUpdateMonitor('current', fetcher)
    const controller = new AbortController()
    const first = monitor.ensureCurrent(controller.signal).catch(error => error)
    const second = monitor.ensureCurrent()
    await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
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
    ['/quotation-sync/logistics', 'POST'],
  ])('preserves recovery/read/accepted-upload action %s %s', (path, method) => expect(requiresCurrentClient(path!, method!)).toBe(false))
})

describe('deliberate page refresh', () => {
  it('preserves the route and existing parameters, and never reloads without confirmation', () => {
    window.history.replaceState(null, '', '/quotation?draft=mine#sheet')
    const navigate = vi.spyOn(window.location, 'assign').mockImplementation(() => undefined)
    const confirm = vi.fn()
    vi.stubGlobal('confirm', confirm)
    refreshUpdatedApplication()
    refreshUpdatedApplication(false)
    expect(navigate).not.toHaveBeenCalled()
    refreshUpdatedApplication(true)
    expect(confirm).not.toHaveBeenCalled()
    const url = new URL(navigate.mock.calls[0]![0] as string)
    expect(url.pathname).toBe('/quotation')
    expect(url.searchParams.get('draft')).toBe('mine')
    expect(url.searchParams.get('_appUpdate')).toBeTruthy()
    expect(url.hash).toBe('#sheet')
  })

  it('refuses even an explicitly confirmed refresh while a request is pending', () => {
    const navigate = vi.spyOn(window.location, 'assign').mockImplementation(() => undefined)
    const end = appUpdate.beginOperation()
    refreshUpdatedApplication(true)
    expect(navigate).not.toHaveBeenCalled()
    end()
    refreshUpdatedApplication()
    expect(navigate).not.toHaveBeenCalled()
    refreshUpdatedApplication(true)
    expect(navigate).toHaveBeenCalledOnce()
  })
})
