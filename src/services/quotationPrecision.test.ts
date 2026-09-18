import { describe, expect, it } from 'vitest'
import { normalizePurchaseRecord } from '@/data/purchaseStore'
import { calculateLogisticsFee, type LogisticsRule } from '@/data/logistics'
import { calculateFinanceQuoteFees } from '@/data/financeSurchargeSettings'
import type { FinanceTaxSettings } from '@/data/financeTaxSettings'
import { bundleGoodsWeight, packagingWeightKg, purchasePriceForMonthlySales, singleActualWeight, usdPriceFromCny, type SingleWeightInput } from './quotationCalculator'
import { displayWeightGrams, sumDecimal, productDecimal } from './quotationDecimal'
import { quoteCnyFromUsd, roundQuoteUsd } from './quotationMoney'

const product: SingleWeightInput = { quantity: 1, netWeight: 0.14, weightSource: 'purchase', manualWeight: 0, volumetricEnabled: false, packageLengthCm: 0, packageWidthCm: 0, packageHeightCm: 0, volumeDivisor: 8000 }
const taxes: FinanceTaxSettings = { countries: [{ country: '美国', fixedFeeUsd: 0.3, selected: true, enabled: true, sortOrder: 10 }], providers: [{ provider: 'carrier', selected: true, mode: 'taxable', channels: [] }], updatedAt: '' }
const surcharges: FinanceTaxSettings = { ...taxes, countries: [] }

function rule(rate1: number, fee1: number, rate2: number, fee2: number): LogisticsRule {
  return {
    id: 1, name: 'test', englishName: '', type: '', currency: 'CNY', published: '', status: '启用', dates: '', users: '', relations: [], phoneRequired: false, areaCount: 1, priceRowCount: 2,
    prices: [[0, 0.2, rate1, fee1], [0.2, 0.45, rate2, fee2]].map(([from, to, rate, fee]) => ({
      areaName: '美国', countryCode: 'US', etaMinDays: 6, etaMaxDays: 14, prohibitedMarks: '', allowedMarks: '', maxPerimeterCm: 0, maxSideCm: 0, volumeDivisor: 0,
      weightFromKg: from, weightToKg: to, startWeightKg: 0, pricePerKg: rate, registrationFee: fee, minChargeWeightKg: 0, firstWeightKg: 0, firstWeightPrice: 0, nextWeightKg: 0, nextWeightPrice: 0, intervalPrice: 0, surcharge: 0, fuelSurchargeRate: 0, prohibitGeneralCargo: false, volumetric: false, phoneRequired: false, zoneName: '', zoneExclude: false,
    })),
  }
}

describe('quotation decimal precision acceptance', () => {
  it('displays 140 + 3 as 143g, and two units as 286g', () => {
    expect(singleActualWeight(product)).toBe(0.143)
    expect(displayWeightGrams(singleActualWeight(product))).toBe(143)
    expect(displayWeightGrams(singleActualWeight(product, 2))).toBe(286)
    expect(displayWeightGrams(singleActualWeight({ ...product, weightSource: 'manual', manualWeight: 0.14 }))).toBe(143)
    const item = { sku: 'A', quantityPerSet: 1, purchaseUnitPrice: 0, purchaseFreightPerUnit: 0, weightKg: 0.14, customWeightKg: null }
    expect(bundleGoodsWeight([item, { ...item, sku: 'B' }])).toBe(0.286)
  })
  it.each([[0.049999, 0.001], [0.05, 0.001], [0.050001, 0.002], [0.15, 0.003], [0.150001, 0.004]])('preserves the real packaging boundary at %s kg', (weight, expected) => {
    expect(packagingWeightKg(weight)).toBe(expected)
  })
  it('keeps an exact upper weight boundary eligible after summing units', () => {
    const shipment = sumDecimal(0.1, 0.2)
    const channel = rule(10, 0, 20, 0)
    channel.prices[0].weightToKg = 0.3
    channel.prices[1].weightFromKg = 0.3
    expect(calculateLogisticsFee(channel, '美国', shipment)?.total).toBe(3)
    expect(calculateLogisticsFee(channel, '美国', 0.300001)?.price.pricePerKg).toBe(20)
  })
  it.each([
    ['O5', 87, 18, 87, 16, 23.75, 43.55, 159.13, 291.79],
    ['cosmetics', 67, 19, 63, 19, 23.40, 42.85, 156.78, 287.10],
    ['tracked', 55, 18, 55, 16, 22.95, 41.90, 153.77, 280.73],
  ] as const)('reproduces both KJ2601048 quotes for %s', (_, rate1, fee1, rate2, fee2, one, two, cny1, cny2) => {
    const record = normalizePurchaseRecord({ sku: 'KJ2601048', purchasePriceCny: 90, taxPoint: 0.08, minOrderQty: 1, weightG: 140, catalogState: 'ready' })
    const purchase = purchasePriceForMonthlySales(record, '10')
    expect(purchase).toBe(97.2)
    for (const [quantity, expected, expectedCny] of [[1, one, cny1], [2, two, cny2]]) {
      const freight = calculateLogisticsFee(rule(rate1, fee1, rate2, fee2), '美国', singleActualWeight(product, quantity))!.total
      const cost = sumDecimal(productDecimal(sumDecimal(purchase, 1.5), quantity), freight)
      const baseUsd = usdPriceFromCny(productDecimal(cost, 1.21605), 6.7)
      const quote = calculateFinanceQuoteFees(taxes, surcharges, '美国', 'carrier', baseUsd)
      expect(quote.totalUsd).toBe(expected)
      expect(quoteCnyFromUsd(quote.totalUsd, 6.7)).toBe(expectedCny)
    }
  })
  it('preserves sub-cent ceilings and decimal half-up conversion', () => {
    expect(roundQuoteUsd(sumDecimal(0.1, 0.2))).toBe(0.3)
    expect(roundQuoteUsd(6.050001)).toBe(6.1)
    expect(quoteCnyFromUsd(6.05, 6.7)).toBe(40.54)
  })
})
