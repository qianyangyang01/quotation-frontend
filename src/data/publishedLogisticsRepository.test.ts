import { beforeEach, describe, expect, it, vi } from 'vitest'

const { conditionalGet } = vi.hoisted(() => ({ conditionalGet: vi.fn() }))
vi.mock('@/services/http', () => ({ conditionalGet }))

const manifest = (revision: string) => ({ revision, generatedAt: '2026-08-24T00:00:00Z', publishedChannels: 1, countries: [{ code: 'US', name: '美国' }], attributes: ['普货'] })
const rule = { id: 1, name: '云途普货', englishName: 'yt', type: '专线', currency: 'CNY', published: '发布', status: '启用', dates: '|', users: '|', relations: [{ carrier: '云途', channel: '云途普货', channelCode: 'YT', discounts: '-' }], phoneRequired: false, areaCount: 1, priceRowCount: 1, prices: [{ areaName: '美国', countryCode: 'US' }] }

describe('published logistics version cache', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.stubGlobal('indexedDB', undefined)
    vi.stubGlobal('BroadcastChannel', undefined)
    conditionalGet.mockReset()
  })

  it('checks the small manifest but does not download unchanged rules twice', async () => {
    let manifestCalls = 0
    conditionalGet.mockImplementation((path: string) => {
      if (path.includes('/manifest')) return Promise.resolve(++manifestCalls === 1
        ? { status: 200, data: manifest('r1'), etag: '"r1"' }
        : { status: 304, data: null, etag: '"r1"' })
      return Promise.resolve({ status: 200, data: { revision: 'r1', rules: [rule] }, etag: '"rules-r1"' })
    })
    const repository = await import('./publishedLogisticsRepository')

    const first = await repository.loadPublishedLogisticsRules({ attribute: '普货', countries: ['美国'] })
    const second = await repository.loadPublishedLogisticsRules({ attribute: '普货', countries: ['美国'] })

    expect(first.source).toBe('network')
    expect(second.source).toBe('cache')
    expect(conditionalGet.mock.calls.filter(([path]) => String(path).includes('/rules'))).toHaveLength(1)
  })

  it('invalidates cached rules when the published revision changes', async () => {
    const manifests = [manifest('r1'), manifest('r2')]
    conditionalGet.mockImplementation((path: string) => {
      if (path.includes('/manifest')) return Promise.resolve({ status: 200, data: manifests.shift() || manifest('r2'), etag: 'etag' })
      const revision = path.includes('revision=r2') ? 'r2' : 'r1'
      return Promise.resolve({ status: 200, data: { revision, rules: [{ ...rule, id: revision === 'r2' ? 2 : 1 }] }, etag: `rules-${revision}` })
    })
    const repository = await import('./publishedLogisticsRepository')
    await repository.loadPublishedLogisticsRules({ attribute: '普货', countries: ['美国'] })

    const validation = await repository.validatePublishedLogisticsRevision()
    const refreshed = await repository.loadPublishedLogisticsRules({ attribute: '普货', countries: ['美国'] })

    expect(validation.changed).toBe(true)
    expect(refreshed.revision).toBe('r2')
    expect(refreshed.rules[0]?.id).toBe(2)
  })
})
