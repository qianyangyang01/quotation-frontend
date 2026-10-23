import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { appUpdate, createAppUpdateMonitor } from './appUpdate'
import { api, request, resetCsrf, setRequestAccount, uploadForm } from './http'

const csrf = () => new Response(JSON.stringify({ data: { headerName: 'X-CSRF', token: 'token' } }))
const version = (buildId = 'new') => new Response(JSON.stringify({ schemaVersion: 1, buildId }))
const data = (value = { saved: true }) => new Response(JSON.stringify({ data: value }))
beforeEach(() => {
  const monitor = createAppUpdateMonitor('current')
  vi.spyOn(appUpdate, 'ensureCurrent').mockImplementation(monitor.ensureCurrent)
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); resetCsrf(); setRequestAccount('') })

describe('version checks at the shared HTTP boundary', () => {
  it.each([404, 503, 200])('keeps a healthy business API usable when metadata returns %s or rollback HTML', async status => {
    const fetcher = vi.fn().mockImplementation(url => {
      if (String(url).endsWith('/auth/csrf')) return Promise.resolve(csrf())
      if (String(url).includes('app-version.json')) return Promise.resolve(new Response('<html>rollback</html>', { status }))
      return Promise.resolve(data())
    })
    vi.stubGlobal('fetch', fetcher)
    for (let i = 0; i < 3; i++) await expect(api.post('/quotations', {})).resolves.toEqual({ saved: true })
    expect(fetcher.mock.calls.filter(([url]) => String(url).includes('app-version.json'))).toHaveLength(1)
    expect(appUpdate.state.activeOperations).toBe(0)
  })
  it.each(['/quotations', '/quotations/fob/preview', '/batch-single-quotations', '/purchase-products/SKU/maintenance', '/finance-settings/exchange-rate', '/users/id'])('never sends stale-page writes to %s', async path => {
    const fetcher = vi.fn().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(version())
    vi.stubGlobal('fetch', fetcher)
    await expect(api.post(path, {})).rejects.toMatchObject({ code: 'CLIENT_UPDATE_REQUIRED' })
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(fetcher.mock.calls.some(([url]) => String(url) === `/api/v1${path}`)).toBe(false)
    expect(appUpdate.state.activeOperations).toBe(0)
  })

  it('holds the refresh lock until an accepted write completes, even when an update is detected in flight', async () => {
    let finish!: (value: Response) => void
    const monitor = createAppUpdateMonitor('current')
    vi.mocked(appUpdate.ensureCurrent).mockImplementation(monitor.ensureCurrent)
    const fetcher = vi.fn().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(version('current'))
      .mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve }))
      .mockResolvedValueOnce(version())
    vi.stubGlobal('fetch', fetcher)
    const pending = api.post('/quotations', {})
    await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
    expect(appUpdate.state.activeOperations).toBe(1)
    await monitor.check()
    finish(data())
    await expect(pending).resolves.toEqual({ saved: true })
    expect(appUpdate.state.activeOperations).toBe(0)
    await expect(api.post('/quotations', {})).rejects.toMatchObject({ code: 'CLIENT_UPDATE_REQUIRED' })
  })

  it('keeps draft save/readback/logout and upload completion available without checking', async () => {
    vi.mocked(appUpdate.ensureCurrent).mockRejectedValue(new Error('old version'))
    vi.stubGlobal('fetch', vi.fn().mockImplementation(url => Promise.resolve(String(url).endsWith('/auth/csrf') ? csrf() : data())))
    await api.put('/quotation-drafts/mine/state', {})
    await api.get('/quotations')
    await api.post('/logistics/rebuild/uploads/id/complete')
    await api.post('/auth/logout')
    await api.post('/quotation-sync/logistics', {})
    expect(appUpdate.ensureCurrent).not.toHaveBeenCalled()
    expect(appUpdate.state.activeOperations).toBe(0)
  })

  it('does not hold the refresh lock for a stalled read-only logistics synchronization POST', async () => {
    let finish!: (value: Response) => void
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(csrf()).mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve })))
    const pending = api.post('/quotation-sync/logistics', {})
    await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
    expect(appUpdate.state.activeOperations).toBe(0)
    expect(appUpdate.ensureCurrent).not.toHaveBeenCalled()
    finish(data())
    await pending
  })

  it('rechecks the account and cancellation after asynchronous version verification', async () => {
    let finish!: () => void
    vi.mocked(appUpdate.ensureCurrent).mockImplementation(() => new Promise<void>(resolve => { finish = resolve }))
    const fetcher = vi.fn().mockResolvedValue(csrf())
    vi.stubGlobal('fetch', fetcher)
    setRequestAccount('A')
    const pending = api.post('/quotations', {}).catch(error => error)
    await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
    setRequestAccount('B'); finish()
    expect(await pending).toMatchObject({ code: 'ACCOUNT_CHANGED' })
    expect(fetcher).toHaveBeenCalledTimes(1)
    setRequestAccount('B')
    const controller = new AbortController()
    const cancelled = request('/quotations', { method: 'POST', signal: controller.signal }).catch(error => error)
    await Promise.resolve()
    controller.abort(new Error('cancelled')); finish()
    expect(await cancelled).toMatchObject({ message: 'cancelled' })
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('blocks XHR upload creation before sending workbook bytes', async () => {
    const send = vi.fn()
    class Xhr { send = send }
    vi.stubGlobal('XMLHttpRequest', Xhr)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(csrf()).mockResolvedValueOnce(version()))
    await expect(uploadForm('/purchase-imports/jobs', new FormData()).promise).rejects.toMatchObject({ code: 'CLIENT_UPDATE_REQUIRED' })
    expect(send).not.toHaveBeenCalled()
    expect(appUpdate.state.activeOperations).toBe(0)
  })

  it('immediately cancels an upload while version verification is pending', async () => {
    let finish!: (value: Response) => void
    const send = vi.fn()
    class Xhr { send = send; abort() {} }
    vi.stubGlobal('XMLHttpRequest', Xhr)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(csrf())
      .mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve })))
    const upload = uploadForm('/purchase-imports/jobs', new FormData())
    const rejected = upload.promise.catch(error => error)
    await vi.waitFor(() => expect(finish).toBeTypeOf('function'))
    upload.cancel()
    expect(await rejected).toMatchObject({ name: 'AbortError' })
    expect(send).not.toHaveBeenCalled()
    expect(appUpdate.state.activeOperations).toBe(0)
    finish(version('current'))
  })
})
