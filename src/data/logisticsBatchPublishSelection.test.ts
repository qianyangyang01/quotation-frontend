import { describe, expect, it } from 'vitest'
import {
  filterPublishableLogisticsVersionIds,
  initialLogisticsVersionPublishSelection,
  logisticsVersionNeedsRiskReview,
  type LogisticsChannelVersionRecord,
} from './logisticsRepository'

function version(id: string, errors: number, highRisk = 0, removed = 0): LogisticsChannelVersionRecord {
  return {
    id, channelId: `channel-${id}`, versionNumber: 1, status: 'draft', fileName: `${id}.xlsx`, sourceHash: id,
    originalFile: null, rows: [], issues: [], diffRows: [],
    summary: { added: 0, price: 0, rule: 0, removed, unchanged: 0, highRisk },
    importedAt: '', importedBy: '', publishedAt: '', publishedBy: '', auditNote: '',
    rowCount: 0, issueCount: 0, diffCount: 0, countryCount: 0, errors, warnings: 0,
  }
}

describe('logistics batch publish selection', () => {
  it('defaults to valid drafts and keeps blocked drafts out of the publish request', () => {
    const versions = [version('valid-1', 0), version('blocked', 2), version('valid-2', 0)]

    expect(initialLogisticsVersionPublishSelection(versions)).toEqual(['valid-1', 'valid-2'])
    expect(filterPublishableLogisticsVersionIds(versions, ['valid-1', 'blocked', 'stale'])).toEqual(['valid-1'])
  })

  it('returns no publishable ids when every draft is blocked', () => {
    const versions = [version('blocked-1', 1), version('blocked-2', 3)]

    expect(initialLogisticsVersionPublishSelection(versions)).toEqual([])
    expect(filterPublishableLogisticsVersionIds(versions, ['blocked-1', 'blocked-2'])).toEqual([])
  })

  it('keeps risk and removal review independent from blocking errors', () => {
    expect(logisticsVersionNeedsRiskReview(version('normal', 0))).toBe(false)
    expect(logisticsVersionNeedsRiskReview(version('risk', 0, 1))).toBe(true)
    expect(logisticsVersionNeedsRiskReview(version('removed', 0, 0, 1))).toBe(true)
  })
})
