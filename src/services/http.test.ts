import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, idempotencyKey, resetCsrf } from './http'

afterEach(() => {
  vi.unstubAllGlobals()
  resetCsrf()
})

describe('quotation API client', () => {
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

  it('creates unique operation-scoped idempotency keys', () => {
    const first = idempotencyKey('quote')
    const second = idempotencyKey('quote')
    expect(first).toMatch(/^quote:/)
    expect(second).not.toBe(first)
  })
})
