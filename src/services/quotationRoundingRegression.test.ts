import { describe, expect, it } from 'vitest'
import { decimal } from './quotationDecimal'
import { usdPriceFromCny } from './quotationCalculator'
import { calculateFinanceQuoteFees, normalizeFinanceSurchargeSettings } from '@/data/financeSurchargeSettings'
import { normalizeFinanceTaxSettings } from '@/data/financeTaxSettings'
import type { ChannelTaxRule } from '@/data/channelTaxRules'
import { calculateLogisticsFee, type LogisticsRule } from '@/data/logistics'
import { normalizeLogisticsPriceRow } from '@/data/logisticsRepository'

const key = '663::百州::C-b234eb3798b06555a44b'
const rule = (mode: ChannelTaxRule['mode'], amount = 0): ChannelTaxRule => ({ key, mode, amount, perKg: 0, currency: 'USD' })
const country = (name: string, channelRule?: ChannelTaxRule) => ({ country: name, selected: true, enabled: true, sortOrder: 1, fixedFeeUsd: 0, channelRules: channelRule ? [channelRule] : [] })
const noSurcharge = normalizeFinanceSurchargeSettings({ countries: [] })

describe('BK2602604-2 rounding regression', () => {
  it('quotes all five Baizhou quantities without an integer-yuan freight ceiling', () => {
    // Published 2026-11-23 tariff: 83/kg up to 1kg, then 80/kg; 12 per shipment.
    const logistics: LogisticsRule = { id: 663, name: 'BZ-欧美服装专线挂号', englishName: '', type: '专线', currency: 'CNY', published: '', status: '启用', dates: '', users: '', relations: [], phoneRequired: false, areaCount: 1, priceRowCount: 2, billingVerified: true, prices: [
      { weightFromKg: .701, weightToKg: 1, pricePerKg: 83 },
      { weightFromKg: 1.001, weightToKg: 5, pricePerKg: 80 },
    ].map(row => normalizeLogisticsPriceRow({ ...row, countryCode: 'US', areaName: '美国', registrationFee: 12, weightFromInclusive: true, weightToInclusive: true, minChargeWeightKg: .001, pricingModel: 'per-kg-1g' })) }
    const taxes = normalizeFinanceTaxSettings({ countries: [country('美国', rule('exempt'))] })
    const before = JSON.stringify({ logistics, taxes })
    const expectedFreights = [71.26, 126.24, 183.36, 240.48, 297.60]
    const expectedQuotes = [22.60, 42.20, 62.20, 82.20, 102.20]
    for (let quantity = 1; quantity <= 5; quantity++) {
      const freight = calculateLogisticsFee(logistics, 'US', decimal('.714').times(quantity).toNumber())!.total
      expect(freight).toBe(expectedFreights[quantity - 1])
      const cost = decimal('51.9').times(quantity).plus(freight).times('1.21605').toNumber()
      expect(calculateFinanceQuoteFees(taxes, noSurcharge, '美国', '百州', usdPriceFromCny(cost, 6.63), key).totalUsd).toBe(expectedQuotes[quantity - 1])
    }
    expect(JSON.stringify({ logistics, taxes })).toBe(before)
  })

  it('quotes New Zealand three-piece total at 52.40 instead of dropping sub-cent precision', () => {
    const taxes = normalizeFinanceTaxSettings({ countries: [country('新西兰', rule('fixed-order', 1.5))] })
    const surcharges = normalizeFinanceSurchargeSettings({ countries: [{ ...country('新西兰'), fixedFeeUsd: .5, exemptChannelKeys: [] }] })
    const saleCny = decimal('51.9').times(3).plus('118.82').times('1.21605').toNumber()
    const result = calculateFinanceQuoteFees(taxes, surcharges, '新西兰', '递四方', usdPriceFromCny(saleCny, 6.63), key)
    expect(result).toMatchObject({ totalUsd: 52.40, taxUsd: 1.5, surchargeUsd: .5 })
  })

  it.each(['no-tax', 'exempt', 'fixed-order'] as const)('preserves a five-cent boundary for channel mode %s', mode => {
    const taxes = normalizeFinanceTaxSettings({ countries: [country('美国', rule(mode, mode === 'fixed-order' ? .3 : 0))] })
    for (const [base, expected] of [[10.05, 10.05], [10.050001, 10.10], [10.049999, 10.05]]) {
      expect(calculateFinanceQuoteFees(taxes, noSurcharge, '美国', '百州', base!, key).totalUsd)
        .toBe(decimal(expected!).plus(mode === 'fixed-order' ? .3 : 0).toNumber())
    }
  })

  it('preserves precision through legacy country duty and EU handling paths', () => {
    const legacy = normalizeFinanceTaxSettings({ countries: [{ ...country('美国'), fixedFeeUsd: .3 }], providers: [{ provider: '百州', selected: true, mode: 'taxable', channels: [] }] })
    expect(calculateFinanceQuoteFees(legacy, noSurcharge, '美国', '百州', 10.050001).totalUsd).toBe(10.4)
    const eu = normalizeFinanceTaxSettings({ countries: [country('欧盟', rule('fixed-order', 3)), { ...country('罗马尼亚'), euTaxMode: 'add-handling', handlingRules: [rule('fixed-order', 1)] }] })
    expect(calculateFinanceQuoteFees(eu, noSurcharge, '罗马尼亚', '百州', 10.050001, key).totalUsd).toBe(14.1)
  })

  it('does not round coefficient-adjusted CNY before exchange conversion', () => {
    expect(usdPriceFromCny(66.631503, 6.63)).toBeCloseTo(10.050000452489, 12)
    const taxes = normalizeFinanceTaxSettings({ countries: [country('美国', rule('exempt'))] })
    expect(calculateFinanceQuoteFees(taxes, noSurcharge, '美国', '百州', usdPriceFromCny(66.631503, 6.63), key).totalUsd).toBe(10.1)
  })
})
