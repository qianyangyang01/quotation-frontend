import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LogisticsRule, LogisticsPriceRow } from '@/data/logistics'
import { loadAdditionalCountryRules, mergeCountryRules, type CountryRulesSnapshot } from './publishedLogisticsCountryLoader'

const { manifest, rules } = vi.hoisted(() => ({ manifest: vi.fn(), rules: vi.fn() }))
vi.mock('@/data/publishedLogisticsRepository', () => ({ loadPublishedLogisticsManifest: manifest, loadPublishedLogisticsRules: rules }))
function rule(countries: string[], id = 1): LogisticsRule {
  const names: Record<string, string> = { US: '美国', GB: '英国', NL: '荷兰', AE: '阿联酋' }
  return { id, logisticsChannelId: `channel${id}`, logisticsVersionId: `version${id}`,
    prices: countries.map(countryCode => ({ countryCode, areaName: names[countryCode] || countryCode, phoneRequired: countryCode === 'NL' } as LogisticsPriceRow)),
  } as LogisticsRule
}
let snapshot: CountryRulesSnapshot
beforeEach(() => {
  vi.resetAllMocks()
  snapshot = { revision: 'r1', attribute: '普货', countries: ['美国', '英国'], rules: [rule(['US', 'GB'])] }
  manifest.mockResolvedValue({ manifest: { revision: 'r1' }, verified: true })
  rules.mockResolvedValue({ revision: 'r1', rules: [rule(['NL'])], verified: true })
})
const signal = () => new AbortController().signal

describe('incremental country logistics', () => {
  it('requests only missing countries and preserves already loaded prices', async () => {
    const result = await loadAdditionalCountryRules(snapshot, ['美国', '荷兰'], signal())
    expect(rules.mock.calls[0]![0]).toEqual({ attribute: '普货', countries: ['荷兰'] })
    expect(rules.mock.calls[0]![1]).toMatchObject({ apply: false, manifestResult: { verified: true } })
    expect(result.rules[0]!.prices.map(row => row.countryCode)).toEqual(['US', 'GB', 'NL'])
    expect(result.rules[0]).toMatchObject({ areaCount: 3, priceRowCount: 3, phoneRequired: true })
    expect(snapshot.rules[0]!.prices).toHaveLength(2)
    expect(result).toMatchObject({ changedCountries: ['荷兰'], replacedSnapshot: false })
  })

  it('replaces the complete snapshot when a publication revision changes', async () => {
    manifest.mockResolvedValue({ manifest: { revision: 'r2' }, verified: true })
    rules.mockResolvedValue({ revision: 'r2', rules: [rule(['NL'], 2)], verified: true })
    const result = await loadAdditionalCountryRules(snapshot, ['荷兰'], signal())
    expect(rules.mock.calls[0]![0].countries).toEqual(['美国', '英国', '荷兰'])
    expect(result.rules.map(rule => rule.id)).toEqual([2])
    expect(result.revision).toBe('r2')
    expect(result).toMatchObject({ changedCountries: ['美国', '英国', '荷兰'], replacedSnapshot: true })
  })

  it('treats an empty country as loaded without dropping the previous countries', async () => {
    rules.mockResolvedValue({ revision: 'r1', rules: [], verified: true })
    const result = await loadAdditionalCountryRules(snapshot, ['荷兰'], signal())
    expect(result.rules).toEqual(snapshot.rules)
    expect(result.countries).toContain('荷兰')
    await loadAdditionalCountryRules(result, ['荷兰'], signal())
    expect(rules).toHaveBeenCalledTimes(1)
  })

  it('rejects stale, mismatched and failed loads without mutating the displayed snapshot', async () => {
    manifest.mockResolvedValueOnce({ manifest: { revision: 'r1' }, verified: false })
    await expect(loadAdditionalCountryRules(snapshot, ['荷兰'], signal())).rejects.toThrow()
    expect(rules).not.toHaveBeenCalled()
    rules.mockResolvedValueOnce({ revision: 'r2', rules: [rule(['NL'])], verified: true })
    await expect(loadAdditionalCountryRules(snapshot, ['荷兰'], signal())).rejects.toThrow()
    rules.mockRejectedValueOnce(new Error('network'))
    await expect(loadAdditionalCountryRules(snapshot, ['荷兰'], signal())).rejects.toThrow('network')
    expect(snapshot.rules[0]!.prices).toHaveLength(2)
  })

  it('discards a result after SKU cancellation', async () => {
    const controller = new AbortController()
    rules.mockImplementation(async () => { controller.abort(); return { revision: 'r1', rules: [rule(['NL'])], verified: true } })
    await expect(loadAdditionalCountryRules(snapshot, ['荷兰'], controller.signal)).rejects.toThrow()
    expect(snapshot.rules[0]!.prices).toHaveLength(2)
  })

  it('merges by stable IDs, replaces overlapping country rows and retains unrelated channels', () => {
    const before = [rule(['US', 'NL']), rule(['GB'], 2)]
    const merged = mergeCountryRules(before, [rule(['NL']), rule(['NL'], 3)], ['荷兰'])
    expect(merged.map(rule => rule.id)).toEqual([1, 2, 3])
    expect(merged[0]!.prices.map(row => row.countryCode)).toEqual(['US', 'NL'])
    expect(merged[1]).toBe(before[1])
    expect(() => mergeCountryRules(before, [{ ...rule(['NL']), logisticsVersionId: 'changed' }], ['荷兰'])).toThrow()
  })

  it('recognizes canonical and legacy country aliases without loading or duplicating them again', async () => {
    snapshot.countries.push('阿联酋')
    snapshot.rules = [rule(['US', 'GB', 'AE'])]
    const result = await loadAdditionalCountryRules(snapshot, ['阿拉伯联合酋长国', 'AE'], signal())
    expect(rules).not.toHaveBeenCalled()
    expect(result.countries).toEqual(snapshot.countries)
    expect(mergeCountryRules(snapshot.rules, [rule(['AE'])], ['阿拉伯联合酋长国'])[0]!.prices).toHaveLength(3)
  })
})
