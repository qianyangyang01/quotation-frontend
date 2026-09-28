import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, request, conditionalGet, downloadFile, idempotencyKey, resetCsrf, uploadForm, setRequestAccount } from './http'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  resetCsrf()
  setRequestAccount('')
})

describe('quotation API client', () => {
  it.each(['/quotations', '/quotations/record/resubmit', '/quotation-drafts/mine/state'])('releases a stalled save request for %s and preserves its uncertain outcome', async path => {
    vi.useFakeTimers()
    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ data: { headerName: 'X-XSRF-TOKEN', token: 'csrf' } })))
      .mockImplementation((_url, init) => new Promise((_resolve, reject) => init.signal?.addEventListener('abort', () => reject(init.signal.reason))))
    vi.stubGlobal('fetch', fetchMock)
    const pending = request(path, { method: path.includes('drafts') ? 'PUT' : 'POST', body: '{}' })
    const result = pending.catch(error => error)
    await vi.advanceTimersByTimeAsync(30_001)
    expect(fetchMock.mock.calls[1]![1].signal?.aborted).toBe(true)
    expect(await result).toMatchObject({ code: 'QUOTATION_REQUEST_TIMEOUT' })
    expect(await result).toMatchObject({ message: expect.stringContaining('未确认') })
    expect(vi.getTimerCount()).toBe(0)
  })

  it('also bounds draft/readiness loading and CSRF preparation without sending a late save', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.fn().mockImplementation((_url, init) => new Promise((_resolve, reject) => init.signal?.addEventListener('abort', () => reject(init.signal.reason))))
    vi.stubGlobal('fetch', fetchMock)
    const pending = api.post('/quotations', {}).catch(error => error)
    await vi.advanceTimersByTimeAsync(30_001)
    expect(await pending).toMatchObject({ code: 'QUOTATION_REQUEST_TIMEOUT' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    for (const path of ['/quotation-readiness', '/quotation-drafts/mine/state']) {
      const read = api.get(path).catch(error => error)
      await vi.advanceTimersByTimeAsync(30_001)
      expect(await read).toMatchObject({ code: 'QUOTATION_REQUEST_TIMEOUT' })
    }
  })

  it('keeps caller cancellation distinct from a save timeout and clears the deadline', async () => {
    vi.useFakeTimers()
    const caller = new AbortController()
    vi.stubGlobal('fetch', vi.fn((_url, init) => new Promise((_resolve, reject) => init.signal.addEventListener('abort', () => reject(init.signal.reason)))))
    const pending = request('/quotations', { method: 'POST', body: '{}', signal: caller.signal }).catch(error => error)
    caller.abort(new Error('caller cancelled'))
    expect(await pending).toMatchObject({ message: 'caller cancelled' })
    expect(vi.getTimerCount()).toBe(0)
  })

  it('clears the save deadline on successful and rejected HTTP responses', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ data: { headerName: 'X-CSRF', token: 'csrf' } })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { id: 'saved' } })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'CONFLICT', message: '已更新', requestId: 'server-id' }), { status: 409 })))
    await expect(api.post('/quotations', {})).resolves.toEqual({ id: 'saved' })
    expect(vi.getTimerCount()).toBe(0)
    await expect(api.post('/quotations', {})).rejects.toMatchObject({ status: 409, requestId: 'server-id' })
    expect(vi.getTimerCount()).toBe(0)
  })

  it('binds business requests to the account displayed when they started and latches a server mismatch', async () => {
    setRequestAccount('employee-a')
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'ACCOUNT_CHANGED', message: '账号已切换' }), { status: 409 }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(api.get('/quotations')).rejects.toMatchObject({ code: 'ACCOUNT_CHANGED' })
    expect(new Headers(fetchMock.mock.calls[0]![1].headers).get('X-Expected-Account')).toBe('EMPLOYEE-A')
    await expect(api.post('/quotations', {})).rejects.toMatchObject({ code: 'ACCOUNT_CHANGED' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
  it('discards an old account response arriving after a new login', async () => {
    let respond!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { respond = resolve })))
    setRequestAccount('A')
    const pending = api.get('/quotations')
    setRequestAccount('B')
    respond(new Response(JSON.stringify({ data: { owner: 'A' } })))
    await expect(pending).rejects.toMatchObject({ code: 'ACCOUNT_CHANGED' })
  })
  it('does not send a mutation if the account changes during CSRF preparation', async () => {
    let respond!: (response: Response) => void
    const fetchMock = vi.fn(() => new Promise<Response>(resolve => { respond = resolve }))
    vi.stubGlobal('fetch', fetchMock)
    setRequestAccount('A')
    const pending = api.post('/quotation-drafts/mine/state', {})
    setRequestAccount('B')
    respond(new Response(JSON.stringify({ data: { headerName: 'X-XSRF-TOKEN', token: 'csrf' } })))
    await expect(pending).rejects.toMatchObject({ code: 'ACCOUNT_CHANGED' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
  it('passes cancellation through CSRF preparation before a mutation', async () => {
    const controller = new AbortController()
    const fetchMock = vi.fn().mockImplementation((_url, init) => new Promise((_resolve, reject) => init.signal.addEventListener('abort', () => reject(init.signal.reason))))
    vi.stubGlobal('fetch', fetchMock)
    const pending = request('/users', { method: 'POST', body: '{}', signal: controller.signal })
    const assertion = expect(pending).rejects.toThrow('cancelled')
    controller.abort(new Error('cancelled'))
    await assertion
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
  it('prepares an authenticated native link and preserves snapshot parameters', async () => {
    const result = { url: '/api/v1/logistics/rebuild/datasets/one/prices.xlsx?snapshot=fixed', filename: '物流价格.xlsx' }
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: result })))
    vi.stubGlobal('fetch', fetchMock)
    await expect(downloadFile(new URLSearchParams({ kind: 'prices', id: 'one' }))).resolves.toEqual(result)
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/logistics/rebuild/downloads/prepare?kind=prices&id=one', expect.objectContaining({ credentials: 'include' }))
  })
  it('rejects external download links and preserves session error handling', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ data: { url: 'https://external.invalid/export', filename: 'price.xlsx' } }))).mockResolvedValueOnce(new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: '请重新登录' }), { status: 401 })))
    await expect(downloadFile(new URLSearchParams())).rejects.toThrow('下载地址不合法')
    await expect(downloadFile(new URLSearchParams())).rejects.toMatchObject({ status: 401, code: 'UNAUTHORIZED' })
  })
  it('returns the data envelope and forwards request id', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'SUCCESS', data: { ok: true } }), {
      status: 200,
      headers: { 'Content-Type': 'application/json', 'X-Request-Id': 'request-1234' },
    }))
    vi.stubGlobal('fetch', fetchMock)
    await expect(api.get('/health-test')).resolves.toEqual({ ok: true })
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/health-test', expect.objectContaining({ credentials: 'include' }))
  })

  it('maps a server validation response to ApiError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'VALIDATION_ERROR', message: '输入错误', requestId: 'request-5678', fieldErrors: [{ field: 'sku', message: '必填' }],
    }), { status: 422, headers: { 'Content-Type': 'application/json' } })))
    await expect(api.get('/invalid')).rejects.toMatchObject({ status: 422, code: 'VALIDATION_ERROR', requestId: 'request-5678' } satisfies Partial<ApiError>)
  })

  it('supports ETag validation without trying to parse a 304 response body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 304, headers: { ETag: '"revision-1"' } }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(conditionalGet('/logistics/published/manifest', { etag: '"revision-1"' })).resolves.toEqual({ status: 304, data: null, etag: '"revision-1"' })
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/logistics/published/manifest', expect.objectContaining({ headers: expect.any(Headers) }))
    expect((fetchMock.mock.calls[0]?.[1]?.headers as Headers).get('If-None-Match')).toBe('"revision-1"')
  })

  it('creates unique operation-scoped idempotency keys', () => {
    const first = idempotencyKey('quote')
    const second = idempotencyKey('quote')
    expect(first).toMatch(/^quote:/)
    expect(second).not.toBe(first)
  })

  it('reports real upload progress and returns the response envelope', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code:'SUCCESS',data:{headerName:'X-CSRF-TOKEN',token:'token'} }),{status:200,headers:{'Content-Type':'application/json'}})))
    const sentHeaders:Record<string,string>={}
    class FakeXhr {
      upload:{onprogress:((event:{loaded:number;total:number;lengthComputable:boolean})=>void)|null}={onprogress:null};status=200;responseText=JSON.stringify({code:'SUCCESS',data:{id:'job-1'}});withCredentials=false;onerror:(()=>void)|null=null;onabort:(()=>void)|null=null;onload:(()=>void)|null=null
      open(){} setRequestHeader(name:string,value:string){sentHeaders[name.toLowerCase()]=value} getResponseHeader(){return 'request-1'} abort(){this.onabort?.()}
      send(){this.upload.onprogress?.({loaded:5,total:10,lengthComputable:true});this.upload.onprogress?.({loaded:10,total:10,lengthComputable:true});this.onload?.()}
    }
    vi.stubGlobal('XMLHttpRequest',FakeXhr)
    const progress:number[]=[];const upload=uploadForm<{id:string}>('/purchase-imports/jobs',new FormData(),event=>progress.push(event.percent),{'Idempotency-Key':'upload-1'})
    await expect(upload.promise).resolves.toEqual({id:'job-1'});expect(progress).toEqual([50,100]);expect(sentHeaders['idempotency-key']).toBe('upload-1')
  })

  it('cancels an upload that is already in flight', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code:'SUCCESS',data:{headerName:'X-CSRF-TOKEN',token:'token'} }),{status:200,headers:{'Content-Type':'application/json'}})))
    let markSent:()=>void=()=>undefined
    const sent=new Promise<void>(resolve=>{markSent=resolve})
    class FakeXhr {
      upload:{onprogress:((event:{loaded:number;total:number;lengthComputable:boolean})=>void)|null}={onprogress:null};status=0;responseText='';withCredentials=false;onerror:(()=>void)|null=null;onabort:(()=>void)|null=null;onload:(()=>void)|null=null
      open(){} setRequestHeader(){} getResponseHeader(){return null} abort(){this.onabort?.()}
      send(){markSent()}
    }
    vi.stubGlobal('XMLHttpRequest',FakeXhr)
    const upload=uploadForm('/logistics/rebuild/datasets/one/imports',new FormData())
    await sent
    upload.cancel()
    await expect(upload.promise).rejects.toMatchObject({name:'AbortError'})
  })
})
