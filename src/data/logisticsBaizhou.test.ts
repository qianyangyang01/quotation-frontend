import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'

const base: LogisticsRule = { id: 999, name: 'BZ-欧美服装专线挂号', englishName: '', type: '专线', currency: 'CNY', published: '', status: '启用', dates: '', users: '', relations: [], phoneRequired: false, areaCount: 1, priceRowCount: 2, billingVerified: true, prices: [
  { countryCode: 'US', areaName: '美国', weightFromKg: 0, weightToKg: .1, pricePerKg: 81, registrationFee: 23 },
  { countryCode: 'US', areaName: '美国', weightFromKg: .101, weightToKg: .2, pricePerKg: 81, registrationFee: 21 },
].map(r => normalizeLogisticsPriceRow({ ...r, weightFromInclusive: r.weightFromKg > 0, weightToInclusive: true, minChargeWeightKg: .001, pricingModel: 'per-kg-1g-ceil-cny', allowedMarks: '服装' })) }

it('rounds BZ503 grams before tier selection and totals after one ticket fee', () => {
  for (const [weight, total] of [[.000001, 24], [.1, 32], [.100001, 30], [.2, 38]]) expect(calculateLogisticsFee(base, 'US', weight!, ['服装'])?.total).toBe(total)
  expect(calculateLogisticsFee(base, 'US', .200001, ['服装'])).toBeNull()
  expect(calculateLogisticsFee(base, 'CA', .1, ['服装'])).toBeNull()
})

it.skipIf(!process.env.BAIZHOU_PACKAGE)('reconciles every source gram and fractional boundary against backend projection', () => {
  const root = process.env.BAIZHOU_PACKAGE!
  const parsed = JSON.parse(readFileSync(`${root}/parsed.json`, 'utf8'))
  const expected = JSON.parse(readFileSync(`${root}/expected.json`, 'utf8')) as { rows: {countryCode: string; weightFromKg: number; weightToKg: number; pricePerKg: number; registrationFee: number}[] }
  const rule = { ...base, prices: parsed.rows.map((r: Record<string, unknown>) => normalizeLogisticsPriceRow({ ...r, allowedMarks: '服装' })) }
  let checked = 0
  for (const row of expected.rows) {
    for (let grams = Math.max(1, Math.round(row.weightFromKg * 1000)); grams <= Math.round(row.weightToKg * 1000); grams++) {
      const centsNumerator = grams * row.pricePerKg + row.registrationFee * 1000
      const expectedTotal = Math.ceil(centsNumerator / 1000)
      expect(calculateLogisticsFee(rule, row.countryCode, grams / 1000, ['服装'])?.total).toBe(expectedTotal)
      checked++
    }
  }
  expect(checked).toBe(30000)
  const cases = JSON.parse(readFileSync(`${root}/billing-cases.json`, 'utf8')) as { samples: {country: string; weightKg: number; total: number}[]; rejected: {country: string; weightKg: number}[] }
  for (const sample of cases.samples) expect(calculateLogisticsFee(rule, sample.country, sample.weightKg, ['服装'])?.total).toBe(sample.total)
  for (const sample of cases.rejected) expect(calculateLogisticsFee(rule, sample.country, sample.weightKg, ['服装'])).toBeNull()
}, 120000)
