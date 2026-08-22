export interface ApiEnvelope<T> {
  requestId: string
  code: string
  message: string
  data: T
  fieldErrors: Array<{ field: string; message: string }>
  timestamp: string
}

export class ApiError extends Error {
  constructor(message: string, public readonly status: number, public readonly code: string, public readonly requestId: string, public readonly fieldErrors: ApiEnvelope<never>['fieldErrors'] = []) { super(message) }
}

const API_BASE = String(import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')
let csrf: { headerName: string; token: string } | null = null

async function parseEnvelope<T>(response: Response): Promise<ApiEnvelope<T>> {
  let body: ApiEnvelope<T>
  try { body = await response.json() as ApiEnvelope<T> }
  catch { throw new ApiError('服务器返回了无法识别的响应', response.status, 'INVALID_RESPONSE', response.headers.get('X-Request-Id') || 'unknown') }
  if (!response.ok) {
    if (response.status === 401 && typeof window !== 'undefined') window.dispatchEvent(new CustomEvent('quotation:session-expired'))
    throw new ApiError(body.message || '请求失败', response.status, body.code || 'REQUEST_FAILED', body.requestId, body.fieldErrors || [])
  }
  return body
}

async function ensureCsrf() {
  if (csrf) return csrf
  const response = await fetch(`${API_BASE}/auth/csrf`, { credentials: 'include', headers: { Accept: 'application/json' } })
  csrf = (await parseEnvelope<{ headerName: string; token: string }>(response)).data
  return csrf
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = String(init.method || 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  headers.set('X-Request-Id', crypto.randomUUID())
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const token = await ensureCsrf()
    headers.set(token.headerName, token.token)
  }
  const response = await fetch(`${API_BASE}${path}`, { ...init, method, headers, credentials: 'include' })
  return (await parseEnvelope<T>(response)).data
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown, idempotencyKey?: string) => request<T>(path, { method: 'POST', body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body), headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined }),
  put: <T>(path: string, body?: unknown, headers?: HeadersInit) => request<T>(path, { method: 'PUT', body: body instanceof FormData ? body : JSON.stringify(body), headers }),
  patch: <T>(path: string, body: unknown) => request<T>(path, { method: 'PATCH', body: JSON.stringify(body) }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}

export function idempotencyKey(prefix = 'web') { return `${prefix}:${crypto.randomUUID()}` }
export function resetCsrf() { csrf = null }
