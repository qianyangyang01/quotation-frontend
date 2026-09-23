import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'

const price = normalizeLogisticsPriceRow({ countryCode: 'KW', areaName: '科威特',
  sourceSheet: '云途全球化妆品类专线挂号', billingStepKg: .1, pricingModel: 'per-kg',
  weightFromKg: 0, weightToKg: 5, minChargeWeightKg: .1, pricePerKg: 74, registrationFee: 75 })
const rule: LogisticsRule = { id: 590, name: '云途全球化妆品类专线挂号', englishName: '',
  type: '专线', currency: 'CNY', published: '', status: '启用', dates: '', users: '', relations: [],
  phoneRequired: false, areaCount: 1, priceRowCount: 1, billingVerified: true, prices: [price] }

it('reconciles every whole-gram shipment up to 5kg against integer-cent arithmetic', () => {
  for (let grams = 1; grams <= 5000; grams++) {
    const units = Math.floor((grams + 99) / 100)
    const result = calculateLogisticsFee(rule, 'KW', grams / 1000)
    expect(result?.chargeWeightKg, `${grams}g weight`).toBe(units / 10)
    expect(result?.total, `${grams}g fee`).toBe((units * 740 + 7500) / 100)
  }
})

it('preserves source rounding through normalization and rounds the parcel after minimum padding', () => {
  expect(price).toMatchObject({ billingStepKg: .1, sourceSheet: '云途全球化妆品类专线挂号' })
  for (const [weight, charge, total] of [[.05,.1,82.4],[.1,.1,82.4],[.100001,.2,89.8],
    [.12,.2,89.8],[.2,.2,89.8],[.21,.3,97.2],[.3,.3,97.2],[.301,.4,104.6],[4.999,5,445],[5,5,445]]) {
    expect(calculateLogisticsFee(rule, '科威特', weight!), `${weight}kg`).toMatchObject({ actualWeightKg: weight, chargeWeightKg: charge, total })
  }
  for (const weight of [0,-1,5.000001]) expect(calculateLogisticsFee(rule, 'KW', weight)).toBeNull()
})

it('selects the price tier using rounded weight and rejects conflicting rounding rules', () => {
  const tiers = { ...rule, prices: [{ ...price, weightToKg: .15 }, { ...price, weightFromKg: .15, pricePerKg: 100 }] }
  expect(calculateLogisticsFee(tiers, 'KW', .12)).toMatchObject({ chargeWeightKg: .2, total: 95 })
  expect(calculateLogisticsFee({ ...rule, prices: [{ ...price, weightToKg: .15 }] }, 'KW', .12)).toBeNull()
  expect(calculateLogisticsFee({ ...rule, prices: [price, { ...price, billingStepKg: 0 }] }, 'KW', .12)).toBeNull()
})

it('keeps unconfigured tariffs unrounded while supporting other explicit steps', () => {
  for (const billingStepKg of [undefined, 0]) {
    const row = { ...price, billingStepKg }
    expect(calculateLogisticsFee({ ...rule, prices: [row] }, 'KW', .12)).toMatchObject({ chargeWeightKg: .12, total: 83.88 })
  }
  for (const patch of [{ countryCode: 'QA' }, { sourceSheet: '云途全球服装专线挂号' }, { sourceSheet: undefined }]) {
    const row = { ...price, ...patch }
    expect(calculateLogisticsFee({ ...rule, prices: [row] }, row.countryCode, .12)?.chargeWeightKg).toBe(.2)
  }
})
