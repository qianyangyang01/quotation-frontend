import { describe, expect, it } from 'vitest'
import { batchReviewStatus, countBatchReviewStatuses } from './logisticsBatchStatus'
import type { BatchResult } from './logisticsRebuild'

const result = (fields: Partial<BatchResult> = {}): BatchResult => ({ channelId: 'c', versionId: 'v', channelName: '渠道', providerName: '物流商', status: 'draft', pricingReady: true, ...fields })

describe('batch publication status', () => {
  it('keeps unchanged imports visible even though their referenced version is published', () => {
    expect(batchReviewStatus(result({ status: 'unchanged' }), 'published')).toBe('unchanged')
  })
  it('reconciles 112 channels into 99 ready and 13 unchanged, then moves published drafts only', () => {
    const results = [...Array.from({ length: 99 }, () => result()), ...Array.from({ length: 13 }, () => result({ status: 'unchanged' }))]
    const statuses = results.map(r => batchReviewStatus(r, r.status === 'unchanged' ? 'published' : 'draft'))
    expect(countBatchReviewStatuses(statuses)).toEqual({ total: 112, draft: 99, unchanged: 13, blocked: 0, published: 0 })
    expect(statuses.filter(s => s === 'unchanged')).toHaveLength(13)
    const published = results.map(r => batchReviewStatus(r, 'published', true))
    expect(countBatchReviewStatuses(published)).toEqual({ total: 112, draft: 0, unchanged: 13, blocked: 0, published: 99 })
  })
  it('does not count blocked, unsupported, missing-version, out-of-scope or superseded drafts as publishable', () => {
    for (const fields of [{ errors: 1 }, { pricingReady: false }, { versionId: undefined }, { channelId: undefined }, { pendingReasons: ['重量重叠'] }, { status: 'failed' }]) {
      expect(batchReviewStatus(result(fields))).toBe('blocked')
    }
    expect(batchReviewStatus(result(), 'draft', false, false)).toBe('blocked')
    expect(batchReviewStatus(result(), 'superseded')).toBe('blocked')
    expect(batchReviewStatus(result({ etaReady: false, etaMissingCount: 1 }))).toBe('draft')
  })
})
