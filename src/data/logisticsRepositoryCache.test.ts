import { beforeEach, describe, expect, it, vi } from 'vitest'

const { get } = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/services/http', () => ({
  api: { get, post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  idempotencyKey: () => 'test-key',
}))

import { invalidateLogisticsWorkspaceCache, loadLogisticsWorkspace, workspaceLogisticsRules } from './logisticsRepository'

describe('logistics workspace request coalescing', () => {
  beforeEach(() => {
    get.mockReset()
    invalidateLogisticsWorkspaceCache()
  })

  it('shares concurrent workspace requests and reuses the result for published rule refresh', async () => {
    get.mockImplementation((path: string) => Promise.resolve({
      items: path.startsWith('/logistics/providers')
        ? [{ id: 'provider-1', name: '云途', code: 'YT', enabled: true, createdAt: '', updatedAt: '' }]
        : path.startsWith('/logistics/channels')
          ? [{ id: 'channel-1', ruleId: 1, providerId: 'provider-1', name: '云途普货', code: 'YT-PH', type: '专线', logisticsAttribute: '普货', enabled: true, currentVersionId: '', createdAt: '', updatedAt: '', _version: 0 }]
          : [],
      page: 0, size: 200, total: 1, totalPages: 1,
    }))

    const [first, second] = await Promise.all([loadLogisticsWorkspace(), loadLogisticsWorkspace()])
    expect(workspaceLogisticsRules(first)).toHaveLength(0)

    expect(first).toBe(second)
    expect(first.providers).toHaveLength(1)
    expect(get).toHaveBeenCalledTimes(4)
  })

  it('normalizes optional review fields from migrated logistics drafts', async () => {
    get.mockImplementation((path: string) => Promise.resolve({
      items: path.startsWith('/logistics/versions') ? [{
        id: 'version-1', channelId: 'channel-1', versionNumber: 1, status: 'draft', fileName: 'legacy.xlsx', sourceHash: 'sha256',
        rows: [{ areaName: '美国', countryCode: 'US' }], importedAt: '', importedBy: '', publishedAt: '', publishedBy: '', auditNote: '',
      }] : [], page: 0, size: 200, total: 1, totalPages: 1,
    }))

    const state = await loadLogisticsWorkspace()

    expect(state.versions[0]?.issues).toEqual([])
    expect(state.versions[0]?.diffRows).toEqual([])
    expect(state.versions[0]?.summary).toEqual({ added: 0, price: 0, rule: 0, removed: 0, unchanged: 0, highRisk: 0 })
  })

  it('projects only the current published version while keeping a disabled formal channel visible', async () => {
    get.mockImplementation((path: string) => Promise.resolve({
      items: path.startsWith('/logistics/providers')
        ? [{ id: 'provider-1', name: '容鼎', code: 'RD', enabled: true, createdAt: '', updatedAt: '' }]
        : path.startsWith('/logistics/channels')
          ? [
              { id: 'formal', ruleId: 1, providerId: 'provider-1', name: '正式渠道', code: 'RD-1', type: '专线', logisticsAttribute: '普货', enabled: false, currentVersionId: 'version-2', createdAt: '', updatedAt: '', _version: 0 },
              { id: 'draft-only', ruleId: 2, providerId: 'provider-1', name: '仅草稿渠道', code: 'RD-2', type: '专线', logisticsAttribute: '普货', enabled: true, currentVersionId: '', createdAt: '', updatedAt: '', _version: 0 },
            ]
          : [
              { id: 'version-2', channelId: 'formal', versionNumber: 2, status: 'published', fileName: 'v2.xlsx', sourceHash: 'v2', rows: [], rowCount: 6, countryCount: 1, importedAt: '', importedBy: 'A', publishedAt: '', publishedBy: 'B', auditNote: '' },
              { id: 'draft-1', channelId: 'draft-only', versionNumber: 1, status: 'draft', fileName: 'v1.xlsx', sourceHash: 'v1', rows: [], rowCount: 6, countryCount: 1, importedAt: '', importedBy: 'A', publishedAt: '', publishedBy: '', auditNote: '' },
            ],
      page: 0, size: 200, total: 2, totalPages: 1,
    }))

    const state = await loadLogisticsWorkspace()
    const rules = workspaceLogisticsRules(state)

    expect(rules).toHaveLength(1)
    expect(rules[0]?.published).toBe('V2')
    expect(rules[0]?.status).toBe('禁用')
  })
})
