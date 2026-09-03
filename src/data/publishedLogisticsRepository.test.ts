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

  it('treats a manifest without quote countries as an empty business result', async () => {
    conditionalGet.mockResolvedValue({
      status: 200,
      data: { ...manifest('empty'), publishedChannels: 0, countries: [] },
      etag: '"empty"',
    })
    const repository = await import('./publishedLogisticsRepository')

    const result = await repository.loadPublishedLogisticsRules({ attribute: '普货', countries: [] })

    expect(result).toMatchObject({ revision: 'empty', rules: [], source: 'manifest', verified: true })
    expect(conditionalGet).toHaveBeenCalledTimes(1)
    expect(conditionalGet.mock.calls[0]?.[0]).toContain('/manifest')
  })

  it('queries only common and currently selected quote countries', async () => {
    const repository = await import('./publishedLogisticsRepository')
    const rareCountries = Array.from({ length: 140 }, (_, index) => ({ country: `国家${index}`, enabled: true, stage: 'rare' }))

    const countries = repository.buildQuoteLogisticsCountryQuery([
      ...rareCountries,
      { country: '美国', enabled: true, stage: 'common' },
      { country: '英国', enabled: true, stage: 'common' },
      { country: '法国', enabled: false, stage: 'common' },
    ], '澳大利亚', ['美国'])

    expect(countries).toEqual(['澳大利亚', '美国', '英国'])
    expect(countries).not.toContain('国家0')
  })

  it('loads the finance channel catalog through one lightweight request', async () => {
    const countries = Array.from({ length: 140 }, (_, index) => ({ code: `C${index}`, name: `国家${index}` }))
    conditionalGet.mockImplementation((path: string) => {
      if (path.includes('/manifest')) return Promise.resolve({
        status: 200,
        data: { ...manifest('finance-r1'), countries },
        etag: 'finance-manifest',
      })
      return Promise.resolve({
        status: 200,
        data: {
          revision: 'finance-r1',
          rules: [{ ...rule, prices: [{ areaName: '美国', countryCode: 'US' }] }],
        },
        etag: 'finance-catalog',
      })
    })
    const repository = await import('./publishedLogisticsRepository')

    const result = await repository.loadPublishedLogisticsRuleCatalog(['普货'], countries.map(country => country.code))
    const catalogCalls = conditionalGet.mock.calls.filter(([path]) => String(path).includes('/catalog'))

    expect(catalogCalls).toHaveLength(1)
    expect(new URL(`https://example.test${catalogCalls[0]?.[0]}`).searchParams.get('revision')).toBe('finance-r1')
    expect(result.rules).toHaveLength(1)
    expect(result.rules[0]?.prices).toHaveLength(1)
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

  it('falls back to the network when IndexedDB opening stalls', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('indexedDB', { open: vi.fn(() => ({})) })
    conditionalGet.mockImplementation((path: string) => Promise.resolve(path.includes('/manifest')
      ? { status: 200, data: manifest('r-timeout'), etag: 'manifest-timeout' }
      : { status: 200, data: { revision: 'r-timeout', rules: [rule] }, etag: 'rules-timeout' }))
    const repository = await import('./publishedLogisticsRepository')

    const loading = repository.loadPublishedLogisticsRules({ attribute: '普货', countries: ['美国'] })
    await vi.advanceTimersByTimeAsync(1600)

    await expect(loading).resolves.toMatchObject({ revision: 'r-timeout', rules: [rule], source: 'network' })
    vi.useRealTimers()
  })

  it('upgrades the cache schema and removes legacy rule payloads', async () => {
    const cleared = vi.fn()
    const closed = vi.fn()
    const completedRequest = (result?: unknown) => {
      const request: Record<string, unknown> = { result }
      queueMicrotask(() => (request.onsuccess as (() => void) | undefined)?.())
      return request
    }
    const objectStore = {
      clear: () => { cleared(); return completedRequest() },
      get: () => completedRequest(undefined),
      put: () => completedRequest(),
    }
    const database = {
      objectStoreNames: { contains: () => true },
      transaction: () => ({ objectStore: () => objectStore }),
      createObjectStore: vi.fn(),
      close: closed,
      onversionchange: null as (() => void) | null,
    }
    const open = vi.fn(() => {
      const request: Record<string, unknown> = {
        result: database,
        transaction: { objectStore: () => objectStore },
      }
      queueMicrotask(() => {
        ;(request.onupgradeneeded as (() => void) | undefined)?.()
        ;(request.onsuccess as (() => void) | undefined)?.()
      })
      return request
    })
    vi.stubGlobal('indexedDB', { open })
    conditionalGet.mockImplementation((path: string) => Promise.resolve(path.includes('/manifest')
      ? { status: 200, data: manifest('r2'), etag: 'manifest-r2' }
      : { status: 200, data: { revision: 'r2', rules: [rule] }, etag: 'rules-r2' }))
    const repository = await import('./publishedLogisticsRepository')

    await expect(repository.loadPublishedLogisticsRules({ attribute: '普货', countries: ['美国'] }))
      .resolves.toMatchObject({ revision: 'r2', source: 'network' })

    expect(open).toHaveBeenCalledWith('milano-quotation-cache', 2)
    expect(cleared).toHaveBeenCalledTimes(1)
    expect(database.onversionchange).toBeTypeOf('function')
    database.onversionchange?.()
    expect(closed).toHaveBeenCalledTimes(1)
  })

  it('falls back to the network when another page blocks the cache upgrade', async () => {
    const open = vi.fn(() => {
      const request: Record<string, unknown> = {}
      queueMicrotask(() => (request.onblocked as (() => void) | undefined)?.())
      return request
    })
    vi.stubGlobal('indexedDB', { open })
    conditionalGet.mockImplementation((path: string) => Promise.resolve(path.includes('/manifest')
      ? { status: 200, data: manifest('blocked-r2'), etag: 'manifest-blocked-r2' }
      : { status: 200, data: { revision: 'blocked-r2', rules: [rule] }, etag: 'rules-blocked-r2' }))
    const repository = await import('./publishedLogisticsRepository')

    await expect(repository.loadPublishedLogisticsRules({ attribute: '普货', countries: ['美国'] }))
      .resolves.toMatchObject({ revision: 'blocked-r2', rules: [rule], source: 'network' })
  })

  it('does not apply a superseded logistics response', async () => {
    let resolveRules!: (value: unknown) => void
    conditionalGet.mockImplementation((path: string) => path.includes('/manifest')
      ? Promise.resolve({ status: 200, data: manifest('abort-r2'), etag: 'manifest-abort-r2' })
      : new Promise(resolve => { resolveRules = resolve }))
    const repository = await import('./publishedLogisticsRepository')
    const { logisticsRules } = await import('./logistics')
    const controller = new AbortController()
    const loading = repository.loadPublishedLogisticsRules({ attribute: '普货', countries: ['美国'] }, { signal: controller.signal })
    while (!resolveRules) await Promise.resolve()

    controller.abort()
    resolveRules({ status: 200, data: { revision: 'abort-r2', rules: [rule] }, etag: 'rules-abort-r2' })

    await expect(loading).rejects.toMatchObject({ name: 'AbortError' })
    expect(logisticsRules).toEqual([])
  })
})
