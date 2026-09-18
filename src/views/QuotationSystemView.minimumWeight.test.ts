import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { expect, it, vi } from 'vitest'
import { calculateLogisticsFee, type LogisticsRule } from '@/data/logistics'
import { normalizeLogisticsPriceRow } from '@/data/logisticsRepository'
import { singleActualWeight, bundleGoodsWeight, usdPriceFromCny } from '@/services/quotationCalculator'
import { productDecimal, sumDecimal } from '@/services/quotationDecimal'
import { quoteCnyFromUsd, roundQuoteUsd } from '@/services/quotationMoney'

const source = readFileSync(new URL('./QuotationSystemView.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const parsed = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
const body = parsed.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'quantityCostBreakdown')!.getText(parsed)
const js = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText

it.each(['single', 'bundle'])('uses the parcel minimum in the live %s quote while keeping customs weight actual', mode => {
  const rule = { billingVerified: true, prices: [normalizeLogisticsPriceRow({ countryCode: 'US', areaName: '美国',
    weightFromKg: .001, weightToKg: .1, weightFromInclusive: true, minChargeWeightKg: .03, pricePerKg: 63, registrationFee: 20 })] } as LogisticsRule
  const product = { sku: 'BK2601848', country: '美国', quantity: 1, netWeight: .01, weightSource: 'purchase' as const,
    manualWeight: 0, volumetricEnabled: false, packageLengthCm: 0, packageWidthCm: 0, packageHeightCm: 0, volumeDivisor: 8000,
    logisticsAttribute: '化妆品', purchase: 100.44, purchaseFreightPerUnit: .5 }
  const item = { sku: 'A', quantityPerSet: 1, weightKg: .005, customWeightKg: null, purchaseUnitPrice: 50.22, purchaseFreightPerUnit: .25 }
  const items = [item, { ...item, sku: 'B' }]
  const taxResult = vi.fn((_country: string, _provider: string, cny: number, _rule: string, _channel: string, _weightKg: number) => ({ totalUsd: roundQuoteUsd(usdPriceFromCny(cny, 6.7) + .3) }))
  let special = 0
  const context = { specialPackagingError: { value: '' }, purchaseTaxBlockReason: { value: '' }, logisticsRuleForChannel: () => rule, normalizedBundleSets: (n: number) => n,
    quoteMode: { value: mode }, bundleGoodsWeight: (n: number) => bundleGoodsWeight(items, n, special), singleActualWeight: (p:typeof product,n:number)=>singleActualWeight(p,n,special), calculateLogisticsFee,
    bundlePurchaseCost: (n: number) => productDecimal(100.44, n), bundleDomesticFreight: (n: number) => productDecimal(.5, n),
    findPurchaseProduct: () => null, purchaseRecords: { value: [] }, purchasePriceForMonthlySales: () => 100.44,
    sumDecimal, productDecimal, selectedGradeCoefficient: () => 1.27635, taxResult, quoteCnyFromUsd, exchange: { value: { usd: 6.7 } }, quoteRegionForCountry: () => '' }
  const calculate = new Function(...Object.keys(context), js + '\nreturn quantityCostBreakdown')(...Object.values(context))
  const one = calculate(product, '燕文', 1)
  const two = calculate(product, '燕文', 2)
  expect(one).toMatchObject({ freight: 21.89, cost: 122.83, quoteUsd: 23.7, quoteCny: 158.79 })
  expect(two).toMatchObject({ freight: 21.89, cost: 223.77, quoteUsd: 42.95, quoteCny: 287.77 })
  expect(taxResult.mock.calls[0]?.[5]).toBe(mode === 'single' ? .011 : .012)
  expect(taxResult.mock.calls[1]?.[5]).toBe(mode === 'single' ? .022 : .024)
  special=.01
  expect(calculate(product,'燕文',1).freight).toBe(21.89)
  expect(calculate(product,'燕文',2).freight).toBe(mode==='single'?22.02:22.14)
  expect(taxResult.mock.calls.at(-1)?.[5]).toBe(mode==='single'?.032:.034)
  context.specialPackagingError.value='输入无效'
  expect(calculate(product,'燕文',2)).toBeNull()
})
