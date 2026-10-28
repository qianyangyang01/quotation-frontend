import { reactive } from 'vue'

const PREFIX = 'milano.quotation-update-recovery.v1:'
const TTL = 24 * 60 * 60 * 1000
type Provider = { scope: () => string; capture: () => Promise<unknown> }
let provider: Provider | undefined
export const quotationRecoveryState = reactive({ available: false, busy: false, prepared: false, error: '' })

/** Per-tab, per-account and per-entry input backup; never a saved quotation. */
export function quotationRecoveryScope(account: string, entry: string) {
  if (!account) throw new Error('登录账号尚未确认，请稍后重试')
  const url = new URL(entry, window.location.origin)
  url.searchParams.delete('_appUpdate')
  url.searchParams.delete('release')
  url.searchParams.sort()
  return JSON.stringify([account, url.pathname, url.search])
}
export function registerQuotationUpdateRecovery(value: Provider) {
  if (provider) throw new Error('报价恢复入口重复，请刷新后重试')
  provider = value
  Object.assign(quotationRecoveryState, { available: true, prepared: false, error: '' })
  return () => {
    if (provider !== value) return
    provider = undefined
    quotationRecoveryState.available = false
    quotationRecoveryState.prepared = false
  }
}
export async function prepareQuotationUpdateRecovery() {
  if (quotationRecoveryState.busy) throw new Error('正在保留报价，请稍候')
  const current = provider
  if (!current) return
  Object.assign(quotationRecoveryState, { busy: true, prepared: false, error: '' })
  try {
    const scope = current.scope()
    const payload = await current.capture()
    if (provider !== current || current.scope() !== scope) throw new Error('报价页面或账号已变化，本次未刷新')
    const serialized = JSON.stringify({ schemaVersion: 1, scope, savedAt: Date.now(), payload })
    const key = PREFIX + scope
    sessionStorage.setItem(key, serialized)
    if (sessionStorage.getItem(key) !== serialized) throw new Error('报价内容未完整保存')
    quotationRecoveryState.prepared = true
  } catch (error) {
    quotationRecoveryState.error = `未能保留当前报价，页面未刷新：${error instanceof Error ? error.message : '浏览器存储不可用'}`
    const failure = new Error(quotationRecoveryState.error)
    Object.assign(failure, { cause: error })
    throw failure
  } finally { quotationRecoveryState.busy = false }
}
export function readQuotationUpdateRecovery<T>(scope: string): T | null {
  let stored: string | null
  try { stored = sessionStorage.getItem(PREFIX + scope) } catch { return null }
  if (!stored) return null
  const envelope = JSON.parse(stored)
  if (envelope.schemaVersion !== 1 || envelope.scope !== scope || !Number.isFinite(envelope.savedAt)) throw new Error('刷新前保留的报价格式无法读取，请先保留当前页面并联系管理员')
  if (Date.now() - envelope.savedAt > TTL || envelope.savedAt > Date.now() + 60_000) {
    sessionStorage.removeItem(PREFIX + scope)
    return null
  }
  return envelope.payload as T
}
export function clearQuotationUpdateRecovery(scope: string) {
  try { sessionStorage.removeItem(PREFIX + scope) } catch { /* No backup can be read from disabled storage. */ }
  quotationRecoveryState.prepared = false
}
