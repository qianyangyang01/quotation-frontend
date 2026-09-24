import { describe, expect, it } from 'vitest'
import { emptyPurchasePasteRow, validatePurchasePaste } from './purchasePaste'

describe('sparse purchase paste', () => {
  it('omits blank fields, normalizes identifiers and preserves explicit zero', () => {
    const row = Object.assign(emptyPurchasePasteRow(), { 3: ' ab-c /1 ', 12: '0', 22: '0%' })
    const result = validatePurchasePaste([row], true)
    expect(result.canSave).toBe(true)
    expect(result.records).toEqual([{ sku: 'AB-C/1', sourceRow: 1, purchasePriceCny: 0, taxPoint: 0 }])
  })
  it('defers missing group members to merged server validation, rejecting invalid supplied values', () => {
    const row = Object.assign(emptyPurchasePasteRow(), { 3: 'AB-C', 8: '10', 14: '9' })
    expect(validatePurchasePaste([row], true).canSave).toBe(true)
    row[8] = '0'; expect(validatePurchasePaste([row], true).canSave).toBe(false)
    row[8] = '10'; row[14] = '-2'; expect(validatePurchasePaste([row], true).canSave).toBe(false)
    row[14] = '9'; row[22] = '101%'; expect(validatePurchasePaste([row], true).canSave).toBe(false)
  })
  it('retains original line numbers through blanks and duplicate rows', () => {
    const a = Object.assign(emptyPurchasePasteRow(), { 3: 'AB-C', 12: '8' })
    const b = Object.assign(emptyPurchasePasteRow(), { 3: ' ab-c ', 12: '10' })
    const result = validatePurchasePaste([emptyPurchasePasteRow(), a, b], true)
    expect(result.records).toEqual([{ sku: 'AB-C', sourceRow: 2, purchasePriceCny: 8 }])
    expect(result.skippedRows).toEqual([{ sku: 'AB-C', sourceRow: 3 }])
  })
})
