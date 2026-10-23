import { reactive, readonly } from 'vue'

const POLL_MS = 60_000
const CHECK_TIMEOUT_MS = 8_000
const UPDATE_MESSAGE = '系统已更新，当前操作未提交。请先保留未保存内容，刷新页面后重新查询并操作。'
const UNAVAILABLE_MESSAGE = '暂时无法确认系统版本，当前操作未提交。请检查网络后重试，当前输入已保留。'

export class ClientUpdateError extends Error {
  readonly status = 409
  constructor(public readonly code: 'CLIENT_UPDATE_REQUIRED' | 'CLIENT_VERSION_UNAVAILABLE') {
    super(code === 'CLIENT_UPDATE_REQUIRED' ? UPDATE_MESSAGE : UNAVAILABLE_MESSAGE)
    this.name = 'ClientUpdateError'
  }
}

/** No business cache or saved quotation is changed by this monitor. */
export function createAppUpdateMonitor(buildId: string, fetchVersion: typeof fetch = (...args) => fetch(...args)) {
  const state = reactive({ updateRequired: false, reason: 'application' as 'application' | 'exchange-rate', checking: false, unavailable: false, activeOperations: 0 })
  let pending: Promise<void> | undefined
  let lastAttempt = 0

  function assertCurrent() {
    if (state.updateRequired) throw new ClientUpdateError('CLIENT_UPDATE_REQUIRED')
  }

  function requireUpdate(reason: 'application' | 'exchange-rate' = 'application') {
    state.reason = reason
    state.updateRequired = true
  }

  function check(): Promise<void> {
    if (!buildId) return Promise.resolve()
    if (pending) return pending
    state.checking = true
    lastAttempt = Date.now()
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), CHECK_TIMEOUT_MS)
    pending = Promise.resolve().then(async () => {
      try {
        const response = await fetchVersion(`${import.meta.env.BASE_URL}app-version.json?_=${Date.now()}`, {
          cache: 'no-store', credentials: 'same-origin', headers: { Accept: 'application/json' }, signal: controller.signal,
        })
        if (!response.ok) throw new Error('Version check failed')
        const value: unknown = await response.json()
        if (!value || typeof value !== 'object' || !('schemaVersion' in value) || value.schemaVersion !== 1
          || !('buildId' in value) || typeof value.buildId !== 'string' || !/^[a-zA-Z0-9-]{1,100}$/.test(value.buildId)) {
          throw new Error('Invalid application version')
        }
        // Keep the warning latched across another deployment/rollback. Only a
        // full reload gives every mounted module a coherent code/data baseline.
        if (value.buildId !== buildId) requireUpdate()
        state.unavailable = false
      } catch {
        state.unavailable = true
      } finally {
        clearTimeout(timeout)
        state.checking = false
        pending = undefined
      }
    })
    return pending
  }

  async function ensureCurrent(signal?: AbortSignal | null) {
    signal?.throwIfAborted()
    assertCurrent()
    if (!buildId) return
    // Never use the polling TTL to approve a write. Coalesce concurrent checks,
    // and allow one caller to cancel without aborting another caller's check.
    const verification = check()
    if (signal) {
      await new Promise<void>((resolve, reject) => {
        const abort = () => { signal.removeEventListener('abort', abort); reject(signal.reason) }
        signal.addEventListener('abort', abort, { once: true })
        verification.then(() => { signal.removeEventListener('abort', abort); resolve() }, reject)
        if (signal.aborted) abort()
      })
    } else await verification
    signal?.throwIfAborted()
    assertCurrent()
    if (state.unavailable) throw new ClientUpdateError('CLIENT_VERSION_UNAVAILABLE')
  }

  function beginOperation() {
    state.activeOperations++
    let ended = false
    return () => { if (!ended) { ended = true; state.activeOperations-- } }
  }

  function start() {
    if (!buildId || typeof window === 'undefined') return () => undefined
    const poll = () => {
      if (document.visibilityState !== 'hidden' && Date.now() - lastAttempt >= 5_000) void check()
    }
    void check()
    const interval = setInterval(poll, POLL_MS)
    window.addEventListener('focus', poll)
    window.addEventListener('online', poll)
    document.addEventListener('visibilitychange', poll)
    return () => {
      clearInterval(interval)
      window.removeEventListener('focus', poll)
      window.removeEventListener('online', poll)
      document.removeEventListener('visibilitychange', poll)
    }
  }

  return { state: readonly(state), check, ensureCurrent, assertCurrent, requireUpdate, beginOperation, start }
}

export const appUpdate = createAppUpdateMonitor(typeof __APP_BUILD_ID__ === 'string' ? __APP_BUILD_ID__ : '')

/** Keep recovery and the completion of an accepted upload possible on old tabs. */
export function requiresCurrentClient(path: string, method: string) {
  if (['GET', 'HEAD', 'OPTIONS'].includes(method)) return false
  const pathname = path.split('?')[0]!
  if (/^\/auth\/(?:login|logout|change-password)$/.test(pathname)) return false
  if (pathname === '/quotation-drafts/mine/state' && ['PUT', 'DELETE'].includes(method)) return false
  if (method === 'POST' && (
    /^\/logistics\/rebuild\/uploads\/[^/]+\/(?:files\/\d+\/chunks\/\d+|complete)$/.test(pathname)
    || /^\/purchase-imports\/jobs\/[^/]+\/(?:image-parts|cancel)$/.test(pathname)
  )) return false
  return true
}

export function refreshUpdatedApplication() {
  if (appUpdate.state.activeOperations > 0) return
  if (!window.confirm('刷新会清除尚未保存的输入。请先复制需要保留的内容，或确认当前草稿已保存。确认现在刷新？')) return
  if (appUpdate.state.activeOperations > 0) return
  const url = new URL(window.location.href)
  url.searchParams.set('_appUpdate', String(Date.now()))
  window.location.assign(url.href)
}
