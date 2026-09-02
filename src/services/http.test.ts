import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, conditionalGet, downloadFile, idempotencyKey, resetCsrf, uploadForm } from './http'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  resetCsrf()
})

describe('quotation API client', () => {
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
    class FakeXhr {
      upload:{onprogress:((event:{loaded:number;total:number;lengthComputable:boolean})=>void)|null}={onprogress:null};status=200;responseText=JSON.stringify({code:'SUCCESS',data:{id:'job-1'}});withCredentials=false;onerror:(()=>void)|null=null;onabort:(()=>void)|null=null;onload:(()=>void)|null=null
      open(){} setRequestHeader(){} getResponseHeader(){return 'request-1'} abort(){this.onabort?.()}
      send(){this.upload.onprogress?.({loaded:5,total:10,lengthComputable:true});this.upload.onprogress?.({loaded:10,total:10,lengthComputable:true});this.onload?.()}
    }
    vi.stubGlobal('XMLHttpRequest',FakeXhr)
    const progress:number[]=[];const upload=uploadForm<{id:string}>('/purchase-imports/jobs',new FormData(),event=>progress.push(event.percent))
    await expect(upload.promise).resolves.toEqual({id:'job-1'});expect(progress).toEqual([50,100])
  })
})
