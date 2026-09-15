import { describe, expect, it } from 'vitest'
import { calculateLogisticsFee, findPriceRow, logisticsUnavailableReason, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import { singleActualWeight, bundleGoodsWeight, usdPriceFromCny } from '@/services/quotationCalculator'
import { productDecimal, sumDecimal } from '@/services/quotationDecimal'
import { calculateFinanceQuoteFees } from './financeSurchargeSettings'
import { quoteCnyFromUsd } from '@/services/quotationMoney'
import type { FinanceTaxSettings } from './financeTaxSettings'

const rule: LogisticsRule = {
  id: 1, name: '燕文化妆品专线', englishName: '', type: '专线', currency: 'CNY', published: 'V4', status: '启用', dates: '', users: '',
  relations: [{ carrier: '燕文', channel: '燕文化妆品专线', channelCode: 'YANWEN', discounts: '' }],
  phoneRequired: false, areaCount: 1, priceRowCount: 1, billingVerified: true,
  prices: [normalizeLogisticsPriceRow({ areaName: '美国', countryCode: 'US', weightFromKg: .001, weightToKg: .1,
    weightFromInclusive: true, weightToInclusive: true, pricePerKg: 63, registrationFee: 20, minChargeWeightKg: .03 })],
}
const product = { quantity: 1, netWeight: .01, weightSource: 'purchase' as const, manualWeight: 0,
  volumetricEnabled: false, packageLengthCm: 0, packageWidthCm: 0, packageHeightCm: 0, volumeDivisor: 8000 }
const taxes: FinanceTaxSettings = { countries: [{ country: '美国', selected: true, enabled: true, fixedFeeUsd: .3, sortOrder: 1 }],
  providers: [{ provider: '燕文', selected: true, mode: 'taxable', channels: [] }], updatedAt: '' }

describe('minimum parcel billing weight', () => {
  it.each([.001, .01, .03, .05, .1, 1])('uses a configured %s kg floor regardless of provider', minimum => {
    for (const provider of ['燕文', '万邦', '云途', '云速递', '容鼎', '通邮', '递四方', '闪电猴', '顺丰', '花海', '极通环球']) {
      const channel = { ...rule, relations: [{ ...rule.relations[0]!, carrier: provider }],
        prices: [{ ...rule.prices[0]!, weightFromKg: minimum, weightToKg: 2, minChargeWeightKg: minimum }] }
      const result = calculateLogisticsFee(channel, 'US', minimum / 2)!
      expect(result.chargeWeightKg).toBe(minimum)
      expect(findPriceRow(channel, 'US', minimum / 2)).toBe(result.price)
      expect(logisticsUnavailableReason(channel, 'US', minimum / 2)).toBe('')
    }
  })

  it('uses the explicit start weight and rejects malformed imported floors', () => {
    const channel = { ...rule, prices: [{ ...rule.prices[0]!, startWeightKg: .05 }] }
    expect(calculateLogisticsFee(channel, 'US', .012)?.chargeWeightKg).toBe(.05)
    const invalid = normalizeLogisticsPriceRow({ ...rule.prices[0]!, minChargeWeightKg: Number.NaN })
    expect(calculateLogisticsFee({ ...rule, prices: [invalid] }, 'US', .012)).toBeNull()
  })
  it('rejects conflicting minimums instead of choosing a cheaper matching row', () => {
    const channel = { ...rule, prices: [rule.prices[0]!, { ...rule.prices[0]!, minChargeWeightKg: .05 }] }
    expect(calculateLogisticsFee(channel, 'US', .012)).toBeNull()
    expect(findPriceRow(channel, 'US', .012)).toBeUndefined()
    expect(logisticsUnavailableReason(channel, 'US', .012)).toContain('多个计费标准')
  })
  it.each([
    [1, .012, .03, 21.89, 23.70, 158.79],
    [2, .024, .03, 21.89, 42.95, 287.77],
    [3, .036, .036, 22.27, 62.25, 417.08],
    [5, .060, .060, 23.78, 101.00, 676.70],
  ])('recalculates BK2601848 at quantity %s using the source minimum', (quantity, actual, charged, freight, usd, cny) => {
    const weight = singleActualWeight(product, quantity)
    expect(weight).toBe(actual)
    const result = calculateLogisticsFee(rule, '美国', weight, ['化妆品'])!
    expect(result).toMatchObject({ actualWeightKg: actual, chargeWeightKg: charged, minChargeWeightKg: .03, total: freight })
    const cost = sumDecimal(productDecimal(sumDecimal(100.44, .5), quantity), result.total)
    const fees = calculateFinanceQuoteFees(taxes, { ...taxes, countries: [] }, '美国', '燕文', usdPriceFromCny(productDecimal(cost, 1.27635), 6.7))
    expect(fees.totalUsd).toBe(usd)
    expect(quoteCnyFromUsd(fees.totalUsd, 6.7)).toBe(cny)
  })

  it.each([[.029999,.03],[.03,.03],[.030001,.030001],[.1,.1]])('handles the minimum and tier endpoints at %s kg', (actual, charged) => {
    expect(calculateLogisticsFee(rule, 'US', actual)?.chargeWeightKg).toBe(charged)
  })

  it('pads once after combining bundle items, and leaves purchase/manual weights unchanged', () => {
    const item = { sku: 'A', quantityPerSet: 1, purchaseUnitPrice: 0, purchaseFreightPerUnit: 0, weightKg: .005, customWeightKg: null }
    const items = [item, { ...item, sku: 'B' }]
    for (const [sets, actual, charged] of [[1,.014,.03],[2,.028,.03],[3,.042,.042]]) {
      const weight = bundleGoodsWeight(items, sets)
      expect(weight).toBe(actual)
      expect(calculateLogisticsFee(rule, 'US', weight)?.chargeWeightKg).toBe(charged)
    }
    const manual = { ...product, weightSource: 'manual' as const, manualWeight: .02 }
    expect(singleActualWeight(manual)).toBe(.022)
    expect(calculateLogisticsFee(rule, 'US', singleActualWeight(manual))?.chargeWeightKg).toBe(.03)
    expect(manual.manualWeight).toBe(.02)
    expect(product.netWeight).toBe(.01)
  })

  it('uses the matched country/zone minimum and does not impose 30g on unconfigured channels', () => {
    const base = rule.prices[0]!
    const scoped = { ...rule, prices: [base, { ...base, countryCode: 'GB', areaName: '英国', minChargeWeightKg: .05 }] }
    expect(calculateLogisticsFee(scoped, 'US', .012)?.total).toBe(21.89)
    expect(calculateLogisticsFee(scoped, 'GB', .012)?.total).toBe(23.15)
    const zones = { ...rule, prices: [{ ...base, zoneName: '近区' }, { ...base, zoneName: '远区', minChargeWeightKg: .05 }] }
    expect(calculateLogisticsFee(zones, 'US', .012, [], undefined, '远区')?.total).toBe(23.15)
    const none = { ...rule, prices: [{ ...base, minChargeWeightKg: 0 }] }
    expect(calculateLogisticsFee(none, 'US', .012)?.total).toBe(20.76)
  })

  it('pads below the first tier while preserving gaps and upper bounds', () => {
    for (const actual of [0,.100001]) {
      expect(calculateLogisticsFee(rule, 'US', actual)).toBeNull()
      expect(findPriceRow(rule, 'US', actual)).toBeUndefined()
      expect(logisticsUnavailableReason(rule, 'US', actual)).not.toBe('')
    }
    const lower = { ...rule, prices: [{ ...rule.prices[0]!, weightFromKg: .05, minChargeWeightKg: .05 }] }
    expect(calculateLogisticsFee(rule, 'US', .0005)?.total).toBe(21.89)
    expect(calculateLogisticsFee(lower, 'US', .049)?.total).toBe(23.15)
    expect(calculateLogisticsFee(lower, 'US', .05)?.total).toBe(23.15)
  })

  it.each([-.01, NaN, Infinity, .101])('rejects invalid minimum %s consistently', minChargeWeightKg => {
    const invalid = { ...rule, prices: [{ ...rule.prices[0]!, minChargeWeightKg }] }
    expect(calculateLogisticsFee(invalid, 'US', .012)).toBeNull()
    expect(logisticsUnavailableReason(invalid, 'US', .012)).toBe('计费规则暂不支持')
  })
})
