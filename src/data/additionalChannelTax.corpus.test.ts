import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { expect, it } from 'vitest'
import { calculateFinanceQuoteTax, type FinanceTaxSettings } from './financeTaxSettings'

const dir = process.env.ADDITIONAL_TAX_REPAIR_DIR
it.skipIf(!dir)('reconciles every new destination and preserves all existing channel tax calculations', () => {
  const read = (name: string) => JSON.parse(readFileSync(join(dir!, name), 'utf8').replace(/^\uFEFF/, ''))
  const baseline = read('live-before.json') as { settings: Record<string, { payload: unknown }>; channels: { ruleId: number; provider: string; code: string; countries: { code: string; name: string }[] }[] }
  const before = baseline.settings['tax-settings']!.payload as FinanceTaxSettings
  const after = read('finance-prepared.json')['tax-settings'] as FinanceTaxSettings
  const report = read('change-report.json') as { channels: { ruleId: number; key: string; name: string; donorKey: string | null; countries: string[] }[] }
  const names = new Map((baseline.settings['country-classification']!.payload as { code: string; country: string }[]).map(c => [c.code, c.country]))
  const checks: unknown[] = []
  const quote = (settings: FinanceTaxSettings, country: string, key: string, weightKg: number) => calculateFinanceQuoteTax(settings, names.get(country) || country, key.split('::')[1]!, 10, { channelKey: key, weightKg, usdCny: 6.7, eurUsd: 1.2 })
  expect(report.channels).toHaveLength(15)
  for (const channel of report.channels) for (const country of channel.countries) for (const weight of [.05, .501, 1, 2]) {
    const result = quote(after, country, channel.key, weight)
    expect(result.configured, `${channel.name}/${country}`).toBe(true)
    if (channel.donorKey) {
      const original = quote(before, country, channel.donorKey, weight)
      expect(result).toMatchObject({ taxUsd: original.taxUsd, included: original.included, feeMode: original.feeMode, totalUsd: original.totalUsd })
    } else expect(result, `${channel.name}/${country}`).toMatchObject({ taxUsd: 0, included: true, feeMode: 'exempt', label: '已含税', totalUsd: 10 })
    checks.push({ channel: channel.key, country, weight, result })
  }
  // Verify AU/GB selection and EU handling changes do not alter any old route.
  let unchanged = 0
  for (const channel of baseline.channels.filter(c => c.ruleId < 628)) for (const country of channel.countries) for (const weight of [.1, 1]) {
    const key = `${channel.ruleId}::${channel.provider}::${channel.code}`
    expect(quote(after, country.code, key, weight)).toEqual(quote(before, country.code, key, weight))
    unchanged++
  }
  writeFileSync(join(dir!, 'tax-calculation-evidence.json'), JSON.stringify({ newRouteChecks: checks.length, unchangedRouteChecks: unchanged, checks }, null, 2))
})
