import { expect, it } from 'vitest'
import fixture from '../../backend/src/test/resources/sf-europe-bulky-20261116.json'
import { calculateLogisticsFee, type LogisticsRule } from './logistics'
import { normalizeLogisticsPriceRow } from './logisticsRepository'
import { calculateFinanceQuoteTax, normalizeFinanceTaxSettings } from './financeTaxSettings'
import { channelTaxAmount, type ChannelTaxRule } from './channelTaxRules'

it('matches all source tariffs at every gram and fraction across the band boundary', () => {
  for (const country of new Set(fixture.rows.map(r => r.countryCode))) {
    const rows = fixture.rows.filter(r => r.countryCode === country)
    const rule: LogisticsRule = { id: 1, name: '欧洲-泡货专线', englishName: '', type: '专线', currency: 'CNY',
      published: '', status: '启用', dates: '', users: '', relations: [], phoneRequired: false,
      areaCount: 1, priceRowCount: 2, billingVerified: true,
      prices: rows.map(r => normalizeLogisticsPriceRow({ ...r, pricingModel: 'per-kg', quoteReady: true })) }
    const max = rows[1]!.weightToKg * 1000
    for (let grams = 1; grams <= max; grams++) {
      const source = rows[grams <= rows[0]!.weightToKg * 1000 ? 0 : 1]!
      const total = Math.round(grams * source.pricePerKg / 10 + source.registrationFee * 100) / 100
      const result = calculateLogisticsFee(rule, country, grams / 1000, ['普货'])
      if (!result || Math.abs(result.total - total) > 1e-8) throw new Error(`${country}/${grams}: ${result?.total} != ${total}`)
    }
    for (const attribute of ['普货', '服装']) {
      const result = calculateLogisticsFee(rule, country, rows[0]!.weightToKg + .0001, [attribute])
      expect(result).toMatchObject({ chargeWeightKg: rows[0]!.weightToKg + .001 })
    }
    expect(calculateLogisticsFee(rule, country, max / 1000 + .0001, ['普货'])).toBeNull()
  }
}, 120_000)

it('uses fixed EU duty and no UK duty at every weight, including Romania without the CD handling fee', () => {
  const key = '999::顺丰::NEW'
  const eu: ChannelTaxRule = { key, mode: 'fixed-order', amount: 5.83, currency: 'USD', perKg: 0 }
  const settings = normalizeFinanceTaxSettings({ countries: [
    { country: '欧盟', selected: true, enabled: true, fixedFeeUsd: 5.83, sortOrder: 1, channelRules: [eu] },
    { country: '英国', selected: true, enabled: true, fixedFeeUsd: 0, sortOrder: 2, channelRules: [{ ...eu, mode: 'no-tax', amount: 0 }] },
    { country: '罗马尼亚', selected: true, enabled: false, fixedFeeUsd: 0, sortOrder: 3, euTaxMode: 'add-handling',
      handlingRules: [{ ...eu, key: '602::顺丰::CD', amount: 42, currency: 'CNY' }] },
  ], providers: [] })
  for (const country of new Set(fixture.rows.map(r => r.areaName))) for (const weightKg of [.001, .5, 1, 2, 3, 15, 30]) {
    expect(calculateFinanceQuoteTax(settings, country, '顺丰', 0, { channelKey: key, weightKg, usdCny: 6.63, eurUsd: 1.16 }))
      .toMatchObject({ configured: true, taxUsd: country === '英国' ? 0 : 5.83 })
  }
  // An inactive historical perKg field does not turn fixed-order duty into weight duty.
  for (const weightKg of [.1, 1, 30]) expect(channelTaxAmount({ ...eu, perKg: 1.5 }, { weightKg })).toBe(5.83)
})
