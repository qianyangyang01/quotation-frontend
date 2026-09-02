import { describe, expect, it } from 'vitest'
import { createLatestRequestGuard, shouldPersistSupplierBase, shouldWarnSupplierUnload } from './supplierRecordWorkflow'

describe('supplier record async workflow', () => {
  it('allows only the newest list request to update the page', () => {
    const guard = createLatestRequestGuard()
    const slowRequest = guard.begin()
    const newestRequest = guard.begin()

    expect(guard.isLatest(slowRequest)).toBe(false)
    expect(guard.isLatest(newestRequest)).toBe(true)
    guard.invalidate()
    expect(guard.isLatest(newestRequest)).toBe(false)
  })

  it('does not persist an unchanged saved record again when only media needs retrying', () => {
    const draft = { name: '供应商A', priceLevel: '居中' }
    const snapshot = JSON.stringify(draft)

    expect(shouldPersistSupplierBase(false, draft, snapshot)).toBe(true)
    expect(shouldPersistSupplierBase(true, draft, snapshot)).toBe(false)
    expect(shouldPersistSupplierBase(true, { ...draft, priceLevel: '市场最低' }, snapshot)).toBe(true)
  })

  it('warns before leaving only for idle unsaved edits', () => {
    expect(shouldWarnSupplierUnload(true, false, false)).toBe(true)
    expect(shouldWarnSupplierUnload(false, false, false)).toBe(false)
    expect(shouldWarnSupplierUnload(true, true, false)).toBe(false)
    expect(shouldWarnSupplierUnload(true, false, true)).toBe(false)
  })
})
