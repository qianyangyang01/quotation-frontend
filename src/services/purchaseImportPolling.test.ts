import { describe, expect, it } from 'vitest'
import { didPurchaseImportDataChange, shouldPollPurchaseImportJobs } from './purchaseImportPolling'

describe('purchase import polling', () => {
  it('polls only while the task center is visible and a task is running', () => {
    expect(shouldPollPurchaseImportJobs(true, ['parsing'])).toBe(true)
    expect(shouldPollPurchaseImportJobs(true, ['completed', 'rollback-queued'])).toBe(true)
    expect(shouldPollPurchaseImportJobs(false, ['parsing'])).toBe(false)
    expect(shouldPollPurchaseImportJobs(true, ['completed', 'failed', 'ready'])).toBe(false)
  })

  it('refreshes purchase data once when a task reaches a data-changing terminal state', () => {
    expect(didPurchaseImportDataChange('importing', 'completed')).toBe(true)
    expect(didPurchaseImportDataChange('rolling-back', 'rolled-back')).toBe(true)
    expect(didPurchaseImportDataChange('completed', 'completed')).toBe(false)
    expect(didPurchaseImportDataChange('rolled-back', 'rolled-back')).toBe(false)
    expect(didPurchaseImportDataChange('ready', 'failed')).toBe(false)
  })
})
