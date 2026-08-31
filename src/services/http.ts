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

export interface UploadProgress { loaded:number;total:number;percent:number;bytesPerSecond:number }
export function uploadForm<T>(path:string,form:FormData,onProgress?:(progress:UploadProgress)=>void){
  const xhr=new XMLHttpRequest();let cancelled=false;let startedAt=performance.now()
  const promise=(async()=>{const token=await ensureCsrf();if(cancelled)throw new DOMException('上传已取消','AbortError');return await new Promise<T>((resolve,reject)=>{
    xhr.open('POST',`${API_BASE}${path}`);xhr.withCredentials=true;xhr.setRequestHeader('Accept','application/json');xhr.setRequestHeader('X-Request-Id',crypto.randomUUID());xhr.setRequestHeader(token.headerName,token.token);startedAt=performance.now()
    xhr.upload.onprogress=event=>{const elapsed=Math.max((performance.now()-startedAt)/1000,0.001);const total=event.lengthComputable?event.total:0;onProgress?.({loaded:event.loaded,total,percent:total?Math.min(100,Math.round(event.loaded*100/total)):0,bytesPerSecond:event.loaded/elapsed})}
    xhr.onerror=()=>reject(new ApiError('网络连接中断，文件未上传完成',xhr.status||0,'UPLOAD_NETWORK_ERROR',xhr.getResponseHeader('X-Request-Id')||'unknown'))
    xhr.onabort=()=>reject(new DOMException('上传已取消','AbortError'))
    xhr.onload=()=>{let body:ApiEnvelope<T>;try{body=JSON.parse(xhr.responseText) as ApiEnvelope<T>}catch{reject(new ApiError('服务器返回了无法识别的响应',xhr.status,'INVALID_RESPONSE',xhr.getResponseHeader('X-Request-Id')||'unknown'));return}if(xhr.status<200||xhr.status>=300){if(xhr.status===401&&typeof window!=='undefined')window.dispatchEvent(new CustomEvent('quotation:session-expired'));reject(new ApiError(body.message||'请求失败',xhr.status,body.code||'REQUEST_FAILED',body.requestId,body.fieldErrors||[]));return}resolve(body.data)}
    xhr.send(form)
  })})()
  return {promise,cancel:()=>{cancelled=true;xhr.abort()}}
}

export type ConditionalGetResult<T> = { status: 200; data: T; etag: string } | { status: 304; data: null; etag: string }

export async function conditionalGet<T>(path: string, options: { etag?: string; signal?: AbortSignal } = {}): Promise<ConditionalGetResult<T>> {
  const headers = new Headers({ Accept: 'application/json', 'X-Request-Id': crypto.randomUUID() })
  if (options.etag) headers.set('If-None-Match', options.etag)
  const response = await fetch(`${API_BASE}${path}`, { method: 'GET', headers, credentials: 'include', signal: options.signal })
  if (response.status === 304) return { status: 304, data: null, etag: response.headers.get('ETag') || options.etag || '' }
  return { status: 200, data: (await parseEnvelope<T>(response)).data, etag: response.headers.get('ETag') || '' }
}

export const api = {
  get: <T>(path: string, init?: RequestInit) => request<T>(path, init),
  post: <T>(path: string, body?: unknown, idempotencyKey?: string) => request<T>(path, { method: 'POST', body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body), headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined }),
  put: <T>(path: string, body?: unknown, headers?: HeadersInit) => request<T>(path, { method: 'PUT', body: body instanceof FormData ? body : JSON.stringify(body), headers }),
  patch: <T>(path: string, body: unknown, headers?: HeadersInit) => request<T>(path, { method: 'PATCH', body: JSON.stringify(body), headers }),
  delete: <T>(path: string, headers?: HeadersInit) => request<T>(path, { method: 'DELETE', headers }),
}

export function idempotencyKey(prefix = 'web') { return `${prefix}:${crypto.randomUUID()}` }
export function resetCsrf() { csrf = null }

/** Authenticated binary downloads share JSON error/session handling with normal API calls. */
export async function downloadFile(path: string, filename: string) {
  const response = await fetch(`${API_BASE}${path}`, { credentials: 'include', headers: { 'X-Request-Id': crypto.randomUUID() } })
  if (!response.ok) { await parseEnvelope(response); return }
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url; anchor.download = filename; anchor.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
