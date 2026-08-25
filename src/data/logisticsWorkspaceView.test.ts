import { describe, expect, it } from 'vitest'
import type { LogisticsChannelRecord, LogisticsChannelVersionRecord, LogisticsWorkspaceState } from './logisticsRepository'
import { logisticsChannelRows, logisticsProviderRows, logisticsRuleDetailColumns, logisticsRuleTabs, logisticsWorkspaceSummary } from './logisticsWorkspaceView'

function channel(id: string, providerId: string, currentVersionId = ''): LogisticsChannelRecord {
  return { id, providerId, currentVersionId, ruleId: Number(id), name: `渠道${id}`, code: `CODE-${id}`, type: '专线', logisticsAttribute: '普货', enabled: true, createdAt: '', updatedAt: '', _version: 1, archived: false, archivedAt: '', archivedBy: '', archiveReason: '' }
}

function version(id: string, channelId: string, status: LogisticsChannelVersionRecord['status'], errors?: number): LogisticsChannelVersionRecord {
  return { id, channelId, status, errors: errors as number, warnings: 0, versionNumber: Number(id.replace(/\D/g, '')) || 1, fileName: '', sourceHash: '', originalFile: null, rows: [], issues: errors === undefined ? [{ level: 'error', row: 2, field: 'price', message: '价格缺失' }] : [], diffRows: [], summary: { added: 0, price: 0, removed: 0, rule: 0, unchanged: 0, highRisk: 0 }, importedAt: '', importedBy: '', publishedAt: '', publishedBy: '', auditNote: '', rowCount: 0, issueCount: 0, diffCount: 0, countryCount: 0 }
}

function workspace(): LogisticsWorkspaceState {
  const published = channel('1', 'p1', 'v1')
  const blocked = channel('2', 'p1')
  const archived = { ...channel('3', 'p1', 'v3'), archived: true }
  return {
    providers: [{ id: 'p1', name: '云途', code: 'YT', enabled: true, createdAt: '', updatedAt: '', _version: 1 }],
    channels: [published, blocked, archived],
    versions: [version('v1', '1', 'published', 0), version('v2', '2', 'draft', undefined), version('v3', '3', 'published', 0)],
    audits: [],
  }
}

describe('logistics workspace published views', () => {
  it('keeps weight restrictions inside rule detail and omits the freight calculator tab', () => {
    expect(logisticsRuleTabs).toEqual(['物流商', '物流渠道', '运费规则', '国家区域'])
    expect(logisticsRuleTabs).not.toContain('重量限制')
    expect(logisticsRuleTabs).not.toContain('运费试算')
    expect(logisticsRuleDetailColumns).toEqual(['国家区域', '重量范围', '计泡系数', '最长边', '最大周长', '商品限制', '每1000g运费', '挂号费', '预计时效', '状态'])
  })

  it('derives channel, published and blocked draft counts without archived channels', () => {
    expect(logisticsWorkspaceSummary(workspace())).toEqual({ channels: 2, published: 1, blockedDrafts: 1 })
  })

  it('falls back to issue levels when an older draft has no error counter', () => {
    const rows = logisticsChannelRows(workspace())
    expect(rows.find(row => row.channel.id === '2')?.blockedErrors).toBe(1)
    expect(rows).toHaveLength(2)
  })

  it('keeps provider totals and formal-version totals separate from blocked drafts', () => {
    expect(logisticsProviderRows(workspace())).toEqual([expect.objectContaining({ channels: 2, published: 1, blockedDrafts: 1 })])
  })

  it('derives the current production-shaped 67 / 62 / 5 summary from records', () => {
    const state = workspace()
    state.channels = Array.from({ length: 67 }, (_, index) => channel(String(index + 1), 'p1', index < 62 ? `published-${index + 1}` : ''))
    state.versions = [
      ...state.channels.slice(0, 62).map((item, index) => version(`published-${index + 1}`, item.id, 'published', 0)),
      ...state.channels.slice(62).map((item, index) => version(`draft-${index + 1}`, item.id, 'draft', 1)),
    ]

    expect(logisticsWorkspaceSummary(state)).toEqual({ channels: 67, published: 62, blockedDrafts: 5 })
  })
})
