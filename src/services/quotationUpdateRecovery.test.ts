// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { clearQuotationUpdateRecovery, prepareQuotationUpdateRecovery, quotationRecoveryScope, quotationRecoveryState, readQuotationUpdateRecovery, registerQuotationUpdateRecovery } from './quotationUpdateRecovery'
let unregister: (() => void) | undefined
afterEach(() => { unregister?.(); unregister = undefined; vi.unstubAllGlobals(); sessionStorage.clear(); vi.restoreAllMocks() })
const scope = () => quotationRecoveryScope('a', '/quotation?reissue=one&_appUpdate=123')

it('round trips inputs only within the same account, tab and entry, excluding update cache parameters', async () => {
  const payload = { sku: 'HC2601658-1', channels: ['special-id'], hidden: ['US'], editedPrice: '21.10' }
  unregister = registerQuotationUpdateRecovery({ scope, capture: async () => payload })
  await prepareQuotationUpdateRecovery()
  expect(quotationRecoveryState.prepared).toBe(true)
  expect(readQuotationUpdateRecovery(quotationRecoveryScope('a', '/quotation?_appUpdate=999&reissue=one&release=latest'))).toEqual(payload)
  expect(readQuotationUpdateRecovery(quotationRecoveryScope('b', '/quotation?reissue=one'))).toBeNull()
  expect(readQuotationUpdateRecovery(quotationRecoveryScope('a', '/quotation?reissue=two'))).toBeNull()
  expect(readQuotationUpdateRecovery(quotationRecoveryScope('a', '/quotation'))).toBeNull()
  clearQuotationUpdateRecovery(scope())
  expect(readQuotationUpdateRecovery(scope())).toBeNull()
})
it('blocks refresh on quota failure and retains the earlier backup', async () => {
  unregister = registerQuotationUpdateRecovery({ scope, capture: async () => ({ sku: 'before' }) })
  await prepareQuotationUpdateRecovery(); unregister()
  unregister = registerQuotationUpdateRecovery({ scope, capture: async () => ({ sku: 'after' }) })
  vi.stubGlobal('sessionStorage', { getItem: sessionStorage.getItem.bind(sessionStorage), setItem: () => { throw new DOMException('Quota', 'QuotaExceededError') } })
  await expect(prepareQuotationUpdateRecovery()).rejects.toThrow('页面未刷新')
  expect(quotationRecoveryState.prepared).toBe(false)
  expect(readQuotationUpdateRecovery(scope())).toEqual({ sku: 'before' })
})
it('rejects account/navigation changes during image capture and observes concurrent calls', async () => {
  let finish!: (value: unknown) => void
  let owner = 'a'
  unregister = registerQuotationUpdateRecovery({ scope: () => owner, capture: () => new Promise(resolve => { finish = resolve }) })
  const pending = prepareQuotationUpdateRecovery()
  await expect(prepareQuotationUpdateRecovery()).rejects.toThrow('正在保留')
  owner = 'b'; finish({ sku: 'private' })
  await expect(pending).rejects.toThrow('账号已变化')
  expect(sessionStorage.length).toBe(0)
})
it('expires abandoned backups and does not block normal entry when storage is unavailable', async () => {
  unregister = registerQuotationUpdateRecovery({ scope, capture: async () => ({ sku: 'old' }) })
  await prepareQuotationUpdateRecovery()
  vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 25 * 60 * 60 * 1000)
  expect(readQuotationUpdateRecovery(scope())).toBeNull()
  vi.stubGlobal('sessionStorage', { getItem: () => { throw new Error('storage denied') } })
  expect(readQuotationUpdateRecovery(scope())).toBeNull()
})
