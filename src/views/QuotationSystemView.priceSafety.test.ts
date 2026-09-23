import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from '@/data/logistics'
import { normalizeLogisticsPriceRow } from '@/data/logisticsRepository'
import { singleActualWeight, bundleGoodsWeight, usdPriceFromCny } from '@/services/quotationCalculator'
import { productDecimal, sumDecimal } from '@/services/quotationDecimal'
import { quoteCnyFromUsd } from '@/services/quotationMoney'
import { calculateFinanceQuoteFees } from '@/data/financeSurchargeSettings'
import { customerOperationFeeForQuantity, addCustomerOperationFee } from '@/data/customerOperationFees'
import { applyCommissionThreshold, COMMISSION_THRESHOLD_ERROR } from '@/services/quotationCommission'

const source = readFileSync(new URL('./QuotationSystemView.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const parsed = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
const functions = ['taxResult', 'quantityCostBreakdown'].map(name => parsed.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === name)!.getText(parsed)).join('\n')
const js = ts.transpileModule(functions, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText

// Independent Python Decimal reference: min 150g, 100g ceiling, CNY 100/kg + 5/order,
// purchase CNY 10 + domestic 1/unit, grade 1.2, FX 6.7; separate 7g parcel packaging.
// Surcharge .50; operation .30/.40/.50/.60 per order; final commission divisor .95.
const cases = [
  ['single','exempt',1,.13,25,36,0,7.65,51.26], ['single','exempt',2,.253,35,57,0,11.75,78.73],
  ['single','exempt',3,.376,45,78,0,15.8,105.86], ['single','exempt',4,.499,55,99,0,19.85,133],
  ['single','fixed-order',1,.13,25,36,3.51,11.4,76.38], ['single','fixed-order',2,.253,35,57,3.51,15.45,103.52],
  ['single','fixed-order',3,.376,45,78,3.51,19.5,130.65], ['single','fixed-order',4,.499,55,99,3.51,23.55,157.79],
  ['single','weight',1,.13,25,36,.92,8.65,57.96], ['single','weight',2,.253,35,57,1.14,12.9,86.43],
  ['single','weight',3,.376,45,78,1.35,17.25,115.58], ['single','weight',4,.499,55,99,1.56,21.5,144.05],
  ['bundle','exempt',1,.131,25,36,0,7.65,51.26], ['bundle','exempt',2,.255,35,57,0,11.75,78.73],
  ['bundle','exempt',3,.379,45,78,0,15.8,105.86], ['bundle','exempt',4,.503,65,109,0,21.75,145.73],
  ['bundle','fixed-order',1,.131,25,36,3.51,11.4,76.38], ['bundle','fixed-order',2,.255,35,57,3.51,15.45,103.52],
  ['bundle','fixed-order',3,.379,45,78,3.51,19.5,130.65], ['bundle','fixed-order',4,.503,65,109,3.51,25.45,170.52],
  ['bundle','weight',1,.131,25,36,.92,8.65,57.96], ['bundle','weight',2,.255,35,57,1.14,12.9,86.43],
  ['bundle','weight',3,.379,45,78,1.36,17.25,115.58], ['bundle','weight',4,.503,65,109,1.57,23.4,156.78],
] as const

it.each(cases)('live %s / %s / quantity %i keeps the entire final-price chain consistent', (mode, taxMode, quantity, weight, freight, cost, taxUsd, quoteUsd, quoteCny) => {
  const key = '579::云速递::TEST'
  const rule = { billingVerified: true, prices: [normalizeLogisticsPriceRow({ countryCode: 'US', areaName: '美国', weightFromKg: 0, weightToKg: 1,
    minChargeWeightKg: .15, billingStepKg: .1, pricePerKg: 100, registrationFee: 5 })], relations: [] } as unknown as LogisticsRule
  const product = { sku: 'SYNTHETIC', country: '美国', quantity: 1, netWeight: .12, weightSource: 'purchase' as const,
    manualWeight: 0, volumetricEnabled: false, packageLengthCm: 0, packageWidthCm: 0, packageHeightCm: 0, volumeDivisor: 8000,
    logisticsAttribute: '普货', purchase: 10, purchaseFreightPerUnit: 1 }
  const items = ['A','B'].map(sku => ({ sku, quantityPerSet: 1, weightKg: .06, customWeightKg: null, purchaseUnitPrice: 5, purchaseFreightPerUnit: .5 }))
  const country = { country: '美国', fixedFeeUsd: 0, selected: true, enabled: true, sortOrder: 1 }
  const context = {
    specialPackagingError: { value: '' }, purchaseTaxBlockReason: { value: '' }, logisticsRuleForChannel: () => rule,
    normalizedBundleSets: (n: number) => n, quoteMode: { value: mode },
    bundleGoodsWeight: (n: number) => bundleGoodsWeight(items, n, .007), singleActualWeight: (p: typeof product, n: number) => singleActualWeight(p, n, .007), calculateLogisticsFee,
    bundlePurchaseCost: (n: number) => productDecimal(10, n), bundleDomesticFreight: (n: number) => n,
    findPurchaseProduct: () => null, purchaseRecords: { value: [] }, purchasePriceForMonthlySales: () => 10,
    sumDecimal, productDecimal, selectedGradeCoefficient: () => 1.2, quoteCnyFromUsd, quoteRegionForCountry: () => '',
    exchange: { value: { usd: 6.7, eurUsd: 1.16 } }, usdPriceFromCny: (n: number) => usdPriceFromCny(n, 6.7),
    calculateFinanceQuoteFees, customerOperationFeeForQuantity, addCustomerOperationFee, applyCommissionThreshold, COMMISSION_THRESHOLD_ERROR,
    financeTaxSettings: { value: { countries: [{ ...country, channelRules: [{ key, mode: taxMode, amount: taxMode === 'weight' ? .6 : 3.51, perKg: 1.5, currency: taxMode === 'weight' ? 'EUR' : 'USD' }] }], providers: [], updatedAt: '' } },
    financeSurchargeSettings: { value: { countries: [{ ...country, fixedFeeUsd: .5 }], providers: [{ provider: '云速递', mode: 'taxable', selected: true, channels: [] }], updatedAt: '' } },
    customerOperation: { value: { configured: true, snapshot: { feeUsd: .3, feesByQuantityUsd: { '1': .3, '2': .4, '3': .5, above3: .6 } } } },
    commissionThreshold: { value: '.95' },
  }
  const calculate = new Function(...Object.keys(context), js + '\nreturn quantityCostBreakdown')(...Object.values(context))
  const actual = calculate(product, 'test', quantity, '美国', '云速递', '', key)
  expect(actual).toMatchObject({ freight, cost, quoteUsd, quoteCny, tax: { configured: true, taxUsd, surchargeUsd: .5, calculation: { weightKg: weight } } })
})
