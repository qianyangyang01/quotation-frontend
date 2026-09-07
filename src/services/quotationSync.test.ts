// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { loadQuotationSync, purchaseRevision, startQuotationSync } from './quotationSync'
const { get } = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('./http', () => ({ api: { get } }))

describe('scoped quotation synchronization', () => {
  let stop: (() => void) | undefined
  beforeEach(() => { vi.useFakeTimers(); vi.spyOn(document, 'hidden', 'get').mockReturnValue(false); get.mockReset() })
  afterEach(() => { stop?.(); vi.useRealTimers(); vi.restoreAllMocks() })
  it('requests only active SKUs without paging or a purchase-wide revision', async () => {
    get.mockResolvedValue({ purchaseVersions: { CURRENT: '1:now' }, logisticsRevision: 'r1' })
    await loadQuotationSync(['CURRENT','CURRENT',''])
    expect(get).toHaveBeenCalledWith('/quotation-sync?sku=CURRENT', expect.objectContaining({ cache: 'no-store' }))
    expect(purchaseRevision({ _version: 1, _updatedAt: 'now' })).toBe('1:now')
    expect(purchaseRevision({ _version: 1, _updatedAt: 'later' })).not.toBe('1:now')
  })
  it('never overlaps slow checks and stops immediately on unmount', async () => {
    let release!: () => void
    const check = vi.fn(() => new Promise<void>(resolve => { release = resolve }))
    stop = startQuotationSync(check, vi.fn())
    window.dispatchEvent(new Event('focus'))
    await vi.advanceTimersByTimeAsync(10000)
    expect(check).toHaveBeenCalledTimes(1)
    release(); await vi.advanceTimersByTimeAsync(5000)
    expect(check).toHaveBeenCalledTimes(2)
    stop(); release(); await vi.advanceTimersByTimeAsync(60000)
    expect(check).toHaveBeenCalledTimes(2)
  })
  it('pauses hidden tabs and recovers after transient failures without a reload', async () => {
    const check = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined)
    const failed = vi.fn()
    stop = startQuotationSync(check, failed)
    await vi.advanceTimersByTimeAsync(0)
    expect(failed).toHaveBeenCalledTimes(1)
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(true)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(30000)
    expect(check).toHaveBeenCalledTimes(1)
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(0)
    expect(check).toHaveBeenCalledTimes(2)
  })
})
