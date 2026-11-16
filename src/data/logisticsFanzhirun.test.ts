import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, isPriceRowEligible, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'

const base: LogisticsRule = { id: 999, name: '帆之润美国专线小包', englishName: '', type: '专线', currency: 'CNY', published: '', status: '启用', dates: '', users: '', relations: [], phoneRequired: false, areaCount: 1, priceRowCount: 5, billingVerified: true, prices: [
  [.05, .2, 62, 17], [.201, .45, 62, 17], [.451, .7, 76, 16], [.701, 1, 76, 12], [1.001, 3, 76, 12],
].map(([weightFromKg, weightToKg, pricePerKg, registrationFee]) => normalizeLogisticsPriceRow({ countryCode: 'US', areaName: '美国', weightFromKg, weightToKg, pricePerKg, registrationFee, weightFromInclusive: true, weightToInclusive: true, minChargeWeightKg: .05, pricingModel: 'per-kg-1g-ceil-cny', allowedMarks: '普货、服装' })) }

it('uses 50g minimum and correct fee after each band boundary for both authorized attributes', () => {
  for (const mark of ['普货', '服装']) {
    for (const [weight, total] of [[.000001, 21], [.05, 21], [.2, 30], [.200001, 30], [.45, 45], [.450001, 51], [.7, 70], [.700001, 66], [1, 88], [1.000001, 89], [3, 240]]) expect(calculateLogisticsFee(base, 'US', weight!, [mark])?.total).toBe(total)
    expect(calculateLogisticsFee(base, 'US', 3.000001, [mark])).toBeNull()
    expect(calculateLogisticsFee(base, 'CA', .1, [mark])).toBeNull()
  }
  expect(isPriceRowEligible(base.prices![0]!, ['香水'])).toBe(false)
})

it.skipIf(!process.env.FANZHIRUN_PACKAGE)('reconciles every gram and boundary using independently extracted source and parsed or published prices', () => {
  const root = process.env.FANZHIRUN_PACKAGE!
  const parsed = JSON.parse(readFileSync(`${root}/parsed.json`, 'utf8'))
  const published = process.env.FANZHIRUN_PUBLISHED ? JSON.parse(readFileSync(`${root}/published-rules.json`, 'utf8'))[0] : null
  const expected = JSON.parse(readFileSync(`${root}/expected.json`, 'utf8')) as { rows: {weightFromKg: number; weightToKg: number; pricePerKg: number; registrationFee: number}[] }
  const rule = { ...base, ...(published ?? {}), prices: (published?.prices ?? parsed.rows).map((r: Record<string, unknown>) => normalizeLogisticsPriceRow(r)) }
  for (const mark of ['普货', '服装']) {
    for (let gram = 1; gram <= 3000; gram++) {
      const charge = Math.max(50, gram)
      const r = expected.rows.find(r => charge >= Math.round(r.weightFromKg * 1000) && charge <= Math.round(r.weightToKg * 1000))!
      expect(calculateLogisticsFee(rule, 'US', gram / 1000, [mark])?.total).toBe(Math.ceil((charge * r.pricePerKg + r.registrationFee * 1000) / 1000))
    }
    const cases = JSON.parse(readFileSync(`${root}/billing-cases.json`, 'utf8')) as { samples: {country: string; weightKg: number; total: number}[]; rejected: {country: string; weightKg: number}[] }
    for (const s of cases.samples) expect(calculateLogisticsFee(rule, s.country, s.weightKg, [mark])?.total).toBe(s.total)
    for (const s of cases.rejected) expect(calculateLogisticsFee(rule, s.country, s.weightKg, [mark])).toBeNull()
  }
})
