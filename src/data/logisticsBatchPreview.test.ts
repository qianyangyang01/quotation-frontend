import { describe, expect, it } from 'vitest'
import { initialLogisticsBatchSelection, logisticsBatchSelectionSummary, type LogisticsBatchPreview } from './logisticsRepository'

function preview(): LogisticsBatchPreview {
  const items = Array.from({ length: 13 }, (_, index) => ({
    fileIndex: index, fileKey: `${index}:hash`, fileName: `燕文-${index}.xlsx`, sourceHash: `hash-${index}`,
    action: index > 10 ? 'blocked' as const : 'match' as const, matchType: 'name' as const, channelId: `channel-${index}`,
    channelName: `燕文渠道${index}`, channelCode: `YW-${index}`, providerId: 'yanwen', providerName: '燕文', providerMatchStatus: 'matched' as const,
    hasDraft: index < 7, archived: false, validRows: index > 10 ? 0 : 6, errors: index > 10 ? 1 : 0, warnings: 0,
    rows: [], issues: index > 10 ? [{ row: 49, field: '国家简码', message: '不能为空', level: 'error' as const }] : [], diffRows: [],
    summary: { added: 0, price: 0, rule: 0, removed: 0, unchanged: 0, highRisk: 0 }, file: null as never,
  }))
  return { providerId: 'yanwen', count: 13, blocking: 2, selectable: 11, replaceDrafts: 7, items }
}

describe('logistics batch preview selection', () => {
  it('selects valid files and keeps blockers unavailable while counting old drafts', () => {
    const value = preview(); const selected = initialLogisticsBatchSelection(value)
    expect(selected).toHaveLength(11)
    expect(logisticsBatchSelectionSummary(value, selected)).toEqual({ selectable: 11, blocked: 2, replaceDrafts: 7 })
  })

  it('updates counts when a user unselects a normal file', () => {
    const value = preview(); const selected = initialLogisticsBatchSelection(value).slice(1)
    expect(logisticsBatchSelectionSummary(value, selected)).toEqual({ selectable: 10, blocked: 2, replaceDrafts: 6 })
  })
})
