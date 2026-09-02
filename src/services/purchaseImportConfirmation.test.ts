import { describe, expect, it } from 'vitest'
import type { PurchaseImportContinuation, PurchaseImportJob } from './purchaseAsyncImports'
import { purchaseImportConfirmationState } from './purchaseImportConfirmation'

function job(overrides: Partial<PurchaseImportJob> = {}): PurchaseImportJob {
  return {
    id: 'job-1', sourceName: '采购.xlsx', status: 'ready', phase: 'ready', totalRows: 31, processedRows: 0,
    validRows: 1, errorRows: 0, addedRows: 1, updatedRows: 0, conflictRows: 0, progressPercent: 100,
    imageParts: 0, imagePartDetails: [], imageErrors: 0, createdAt: '', updatedAt: '', ...overrides,
  }
}

function continuation(overrides: Partial<PurchaseImportContinuation> = {}): PurchaseImportContinuation {
  return {
    mode: 'append', sourceName: '采购.xlsx', baselineFound: true, skippedRows: 30, pendingRows: 1, blocked: false,
    sheets: [{ sheetName: '采购', lastImportedRow: 31, nextRow: 32, skippedRows: 30, newRows: 1, retryRows: 0 }],
    ...overrides,
  }
}

const groups = [{ sku: 'A-1', choices: [{ sourceSheet: '采购', sourceRow: 32 }, { sourceSheet: '采购', sourceRow: 33 }] }]

describe('purchase import confirmation', () => {
  it('counts only importable rows, excluding history and errors', () => {
    const state = purchaseImportConfirmationState(job({ errorRows: 2, summary: { continuation: continuation({ pendingRows: 3 }) } }), [], {})
    expect(state.count).toBe(1)
    expect(state.canConfirm).toBe(true)
    expect(state.continuation?.skippedRows).toBe(30)
  })

  it('does not offer confirmation for an unchanged workbook or error-only pending rows', () => {
    const unchanged = purchaseImportConfirmationState(job({ summary: { continuation: continuation({ pendingRows: 0 }) } }), [], {})
    expect(unchanged.canReview).toBe(false)
    expect(unchanged.canConfirm).toBe(false)
    const errorsOnly = purchaseImportConfirmationState(job({ validRows: 0, errorRows: 1, summary: { continuation: continuation() } }), [], {})
    expect(errorsOnly.canReview).toBe(false)
  })

  it('blocks confirmation after historical rows changed even when valid new rows exist', () => {
    const state = purchaseImportConfirmationState(job({ summary: { continuation: continuation({ blocked: true, reason: '采购第 20 行已改变' }) } }), [], {})
    expect(state.count).toBe(1)
    expect(state.canReview).toBe(false)
    expect(state.canConfirm).toBe(false)
  })

  it('allows retry rows even when there are no newly appended rows', () => {
    const state = purchaseImportConfirmationState(job({ summary: { continuation: continuation({ sheets: [{ sheetName: '采购', lastImportedRow: 31, nextRow: 20, skippedRows: 29, newRows: 0, retryRows: 1 }] }) } }), [], {})
    expect(state.canConfirm).toBe(true)
    expect(state.continuation?.sheets[0]?.nextRow).toBe(20)
  })

  it('confirms a SKU-only backfill without counting it twice or requiring new product rows', () => {
    const state = purchaseImportConfirmationState(job({ addedRows: 0, updatedRows: 0, summary: { continuation: continuation({
      skuBackfillRows: 1,
      sheets: [{ sheetName: '采购', lastImportedRow: 31, nextRow: 20, skippedRows: 29, newRows: 0, retryRows: 0, skuBackfillRows: 1 }],
    }) } }), [], {})
    expect(state.count).toBe(1)
    expect(state.skuBackfillCount).toBe(1)
    expect(state.canConfirm).toBe(true)
    expect(state.continuation?.sheets[0]?.nextRow).toBe(20)
  })

  it('keeps an occupied or mismatched SKU blocked even when a backfill is detected', () => {
    const state = purchaseImportConfirmationState(job({ addedRows: 0, summary: { continuation: continuation({
      skuBackfillRows: 1, blocked: true, reason: '目标 SKU 已被其他商品占用',
    }) } }), [], {})
    expect(state.skuBackfillCount).toBe(1)
    expect(state.canReview).toBe(false)
    expect(state.canConfirm).toBe(false)
  })

  it('keeps the planned backfill count separate from importable rows after a write conflict', () => {
    const state = purchaseImportConfirmationState(job({ status: 'completed-with-errors', validRows: 0, conflictRows: 1,
      addedRows: 0, updatedRows: 0, summary: { continuation: continuation({ skuBackfillRows: 1 }) },
    }), [], {})
    expect(state.skuBackfillCount).toBe(1)
    expect(state.count).toBe(0)
    expect(state.canConfirm).toBe(false)
  })

  it('counts one retained row per duplicate group and requires a choice from that group', () => {
    const pending = job({ validRows: 0, conflictRows: 2, summary: { continuation: continuation({ pendingRows: 2 }) } })
    expect(purchaseImportConfirmationState(pending, [], {}).canReview).toBe(false)
    const unresolved = purchaseImportConfirmationState(pending, groups, {})
    expect(unresolved.count).toBe(1)
    expect(unresolved.canReview).toBe(true)
    expect(unresolved.canConfirm).toBe(false)
    expect(purchaseImportConfirmationState(pending, groups, { 'A-1': { sourceSheet: '其他表', sourceRow: 32 } }).canConfirm).toBe(false)
    expect(purchaseImportConfirmationState(pending, groups, { 'A-1': { sourceSheet: '采购', sourceRow: 33 } }).canConfirm).toBe(true)
  })

  it('preserves legacy confirmation without claiming an append baseline', () => {
    const state = purchaseImportConfirmationState(job(), [], {})
    expect(state.continuation).toBeUndefined()
    expect(state.skuBackfillCount).toBe(0)
    expect(state.canConfirm).toBe(true)
  })

  it('never confirms a task that is not ready or has not loaded', () => {
    for (const status of ['parsing', 'importing', 'completed', 'failed', 'cancelled', 'rolled-back'] as const) {
      expect(purchaseImportConfirmationState(job({ status }), [], {}).canConfirm).toBe(false)
    }
    expect(purchaseImportConfirmationState(null, [], {}).canConfirm).toBe(false)
  })
})
