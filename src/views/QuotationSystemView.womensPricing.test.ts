import { defaultWomensPricing, womensPricingSample } from '@/data/womensPricing'
import { isWomensQuotation } from '@/data/quotationModes'
import { quotationFunctionContext } from '@/test/quotationFunctionContext'
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
const functions = ['selectedGradeCoefficient', 'taxResult', 'quantityCostBreakdown'].map(name => parsed.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === name)!.getText(parsed)).join('\n')
const js = ts.transpileModule(functions, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText

// Independent Python Decimal golden values: goods 59 + domestic 1 per unit/set; FX 6.7; band reference = cost USD × 1.23, final = original cost × hit factor.
// Five quantities cross all four bands, then tax, surcharge, operation fee and commission.
const cases = [
  ['womens-single','exempt',1,0.130,25,85,0,17.85,119.60],
  ['womens-single','exempt',2,0.253,35,155,0,31.45,210.72],
  ['womens-single','exempt',3,0.376,45,225,0,44.60,298.82],
  ['womens-single','exempt',4,0.499,55,295,0,57.75,386.93],
  ['womens-single','exempt',5,0.622,75,375,0,73.10,489.77],
  ['womens-single','fixed-order',1,0.130,25,85,3.51,21.55,144.39],
  ['womens-single','fixed-order',2,0.253,35,155,3.51,35.15,235.51],
  ['womens-single','fixed-order',3,0.376,45,225,3.51,48.30,323.61],
  ['womens-single','fixed-order',4,0.499,55,295,3.51,61.45,411.72],
  ['womens-single','fixed-order',5,0.622,75,375,3.51,76.75,514.23],
  ['womens-single','weight',1,0.130,25,85,0.92,18.80,125.96],
  ['womens-single','weight',2,0.253,35,155,1.14,32.65,218.76],
  ['womens-single','weight',3,0.376,45,225,1.35,46.0,308.20],
  ['womens-single','weight',4,0.499,55,295,1.56,59.40,397.98],
  ['womens-single','weight',5,0.622,75,375,1.78,74.95,502.17],
  ['womens-bundle','exempt',1,0.131,25,85,0,17.85,119.60],
  ['womens-bundle','exempt',2,0.255,35,155,0,31.45,210.72],
  ['womens-bundle','exempt',3,0.379,45,225,0,44.60,298.82],
  ['womens-bundle','exempt',4,0.503,65,305,0,59.65,399.66],
  ['womens-bundle','exempt',5,0.627,75,375,0,73.10,489.77],
  ['womens-bundle','fixed-order',1,0.131,25,85,3.51,21.55,144.39],
  ['womens-bundle','fixed-order',2,0.255,35,155,3.51,35.15,235.51],
  ['womens-bundle','fixed-order',3,0.379,45,225,3.51,48.30,323.61],
  ['womens-bundle','fixed-order',4,0.503,65,305,3.51,63.35,424.45],
  ['womens-bundle','fixed-order',5,0.627,75,375,3.51,76.75,514.23],
  ['womens-bundle','weight',1,0.131,25,85,0.92,18.80,125.96],
  ['womens-bundle','weight',2,0.255,35,155,1.14,32.65,218.76],
  ['womens-bundle','weight',3,0.379,45,225,1.36,46.0,308.20],
  ['womens-bundle','weight',4,0.503,65,305,1.57,61.35,411.05],
  ['womens-bundle','weight',5,0.627,75,375,1.79,74.95,502.17]
] as const

it.each(cases)('live %s / %s / quantity %i keeps the entire final-price chain consistent', (mode, taxMode, quantity, weight, freight, cost, taxUsd, quoteUsd, quoteCny) => {
  const key = '579::云速递::TEST'
  const rule = { billingVerified: true, prices: [normalizeLogisticsPriceRow({ countryCode: 'US', areaName: '美国', weightFromKg: 0, weightToKg: 1,
    minChargeWeightKg: .15, billingStepKg: .1, pricePerKg: 100, registrationFee: 5 })], relations: [] } as unknown as LogisticsRule
  const product = { sku: 'SYNTHETIC', country: '美国', quantity: 1, netWeight: .12, weightSource: 'purchase' as const,
    manualWeight: 0, volumetricEnabled: false, packageLengthCm: 0, packageWidthCm: 0, packageHeightCm: 0, volumeDivisor: 8000,
    logisticsAttribute: '普货', purchase: 59, purchaseFreightPerUnit: 1 }
  const items = ['A','B'].map(sku => ({ sku, quantityPerSet: 1, weightKg: .06, customWeightKg: null, purchaseUnitPrice: 29.5, purchaseFreightPerUnit: .5 }))
  const country = { country: '美国', fixedFeeUsd: 0, selected: true, enabled: true, sortOrder: 1 }
  const context = { ...quotationFunctionContext, manualMode: { value: false },
    specialPackagingError: { value: '' }, purchaseTaxBlockReason: { value: '' }, logisticsRuleForChannel: () => rule,
    normalizedBundleSets: (n: number) => n, quoteMode: { value: mode },
    bundleGoodsWeight: (n: number) => bundleGoodsWeight(items, n, .007), singleActualWeight: (p: typeof product, n: number) => singleActualWeight(p, n, .007), calculateLogisticsFee,
    bundlePurchaseCost: (n: number) => productDecimal(59, n), bundleDomesticFreight: (n: number) => n,
    findPurchaseProduct: () => null, purchaseRecords: { value: [] }, purchasePriceForMonthlySales: () => 59,
    sumDecimal, productDecimal, isWomensQuotation, womensPricingSample, womensPricing: {value: defaultWomensPricing()}, quoteCnyFromUsd, quoteRegionForCountry: () => '',
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
