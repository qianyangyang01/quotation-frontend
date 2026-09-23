import { expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { calculateLogisticsFee, validBillingSteps, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'

const cases = JSON.parse(readFileSync('backend/src/test/resources/logistics-rounding-20260923.json', 'utf8')) as {
  channel: string; source: Parameters<typeof normalizeLogisticsPriceRow>[0];
  expectedBands: { aboveKg: number; stepKg: number }[] | null;
  samples: { weight: number; charge: number | null; fee: number | null }[]
}[]
it('reconciles source-scoped tariffs with an independent decimal oracle, including unchanged channels', () => {
  for (const sampleCase of cases) {
    const price = normalizeLogisticsPriceRow({ ...sampleCase.source, billingStepBands: sampleCase.expectedBands ?? undefined })
    const rule: LogisticsRule = { id: 1, name: sampleCase.channel, englishName: '', type: '专线', currency: 'CNY',
      published: '', status: '启用', dates: '', users: '', relations: [], phoneRequired: false,
      areaCount: 1, priceRowCount: 1, billingVerified: true, prices: [price] }
    for (const sample of sampleCase.samples) {
      const result = calculateLogisticsFee(rule, price.countryCode, sample.weight, ['普货'], undefined, price.zoneName.split(/[/／、,，;；|]/)[0])
      const context = `${sampleCase.channel}/${price.countryCode}/${sample.weight}`
      if (sample.fee === null) expect(result, context).toBeNull()
      else expect(result, context).toMatchObject({ chargeWeightKg: sample.charge, total: sample.fee })
    }
  }
})
it('rejects invalid increments and unordered or ambiguous tiered rounding', () => {
  for (const billingStepKg of [-.1, Number.NaN, Infinity]) expect(validBillingSteps({ billingStepKg })).toBe(false)
  for (const billingStepBands of [[], [{ aboveKg: 1, stepKg: .1 }], [{ aboveKg: 0, stepKg: -.1 }],
    [{ aboveKg: 0, stepKg: .1 }, { aboveKg: 0, stepKg: 1 }]]) expect(validBillingSteps({ billingStepBands })).toBe(false)
  expect(validBillingSteps({ billingStepKg: .1, billingStepBands: [{ aboveKg: 0, stepKg: .5 }] })).toBe(false)
})
