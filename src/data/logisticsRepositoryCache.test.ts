import { beforeEach, describe, expect, it, vi } from 'vitest'

const { get } = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/services/http', () => ({
  api: { get, post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  idempotencyKey: () => 'test-key',
}))

import { invalidateLogisticsWorkspaceCache, loadLogisticsWorkspace, refreshPublishedLogisticsRules } from './logisticsRepository'

describe('logistics workspace request coalescing', () => {
  beforeEach(() => {
    get.mockReset()
    invalidateLogisticsWorkspaceCache()
  })

  it('shares concurrent workspace requests and reuses the result for published rule refresh', async () => {
    get.mockResolvedValue({
      providers: [{ id: 'provider-1', name: '云途', code: 'YT', enabled: true, createdAt: '', updatedAt: '' }],
      channels: [{ id: 'channel-1', ruleId: 1, providerId: 'provider-1', name: '云途普货', code: 'YT-PH', type: '专线', logisticsAttribute: '普货', enabled: true, currentVersionId: '', createdAt: '', updatedAt: '', _version: 0 }],
      versions: [],
    })

    const [first, second] = await Promise.all([loadLogisticsWorkspace(), loadLogisticsWorkspace()])
    await refreshPublishedLogisticsRules(first)

    expect(first).toBe(second)
    expect(first.providers).toHaveLength(1)
    expect(get).toHaveBeenCalledTimes(1)
  })
})
