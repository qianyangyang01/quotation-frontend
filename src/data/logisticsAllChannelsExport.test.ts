import { afterEach, describe, expect, it, vi } from 'vitest'
import { logisticsRebuild } from './logisticsRebuild'

afterEach(() => vi.unstubAllGlobals())

describe('all logistics channels export', () => {
  it('requests the complete current dataset without country, provider, version or pagination filters', async () => {
    const download = { url: '/api/v1/logistics/rebuild/datasets/library-a/prices.xlsx?snapshot=all-current', filename: '物流价格.xlsx' }
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: download })))
    vi.stubGlobal('fetch', fetch)
    await expect(logisticsRebuild.exportAllChannels('library-a')).resolves.toEqual(download)
    const params = new URL(fetch.mock.calls[0]![0], 'https://example.test').searchParams
    expect(Object.fromEntries(params)).toEqual({ kind: 'prices', id: 'library-a' })
    expect(fetch.mock.calls[0]![1].credentials).toBe('include')
  })

  it('reports an empty library instead of producing a success link', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'UNPROCESSABLE_ENTITY', message: '没有可导出的价格版本，请先审核价格或选择具体版本' }), { status: 422 })))
    await expect(logisticsRebuild.exportAllChannels('empty-library')).rejects.toThrow('没有可导出的价格版本')
  })
})
