import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { afterEach, expect, it } from 'vitest'
import { logisticsRuleForChannel, replaceLogisticsRules, calculateLogisticsFee, type LogisticsRule } from '@/data/logistics'
import { productDecimal, sumDecimal } from '@/services/quotationDecimal'
import { quoteCnyFromUsd, roundQuoteUsd } from '@/services/quotationMoney'

const source = readFileSync(new URL('./QuotationSystemView.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const ast = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
const names = ['quantityCostBreakdown', 'useLogistics']
const js = ts.transpileModule(ast.statements.filter(n => ts.isFunctionDeclaration(n) && names.includes(n.name?.text || '')).map(n => n.getText(ast)).join('\n'), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
const rules = [0, 1].map(i => ({ id: 9100 + i, name: '同名渠道', billingVerified: true,
  relations: [{ carrier: '燕文', channel: '同名渠道', channelCode: `DEEP-${i}` }],
  prices: [{ areaName: '美国', countryCode: 'US', weightFromKg: 0, weightToKg: i ? 1 : 30, pricingModel: 'per-kg',
    quoteReady: true, pricePerKg: 40 + i * 5, registrationFee: 6 + i, minChargeWeightKg: 0.05,
    startWeightKg: 0, firstWeightKg: 0, firstWeightPrice: 0, nextWeightKg: 0, nextWeightPrice: 0,
    intervalPrice: 0, surcharge: 0, fuelSurchargeRate: 0, volumetric: false, zoneName: '', prohibitedMarks: '', allowedMarks: '' }],
} as LogisticsRule))
afterEach(() => replaceLogisticsRules([]))

it.each(['single', 'bundle'])('prices same-name routes independently in %s, including weight rejection and adopted identity', mode => {
  replaceLogisticsRules(rules)
  const context = { logisticsRuleForChannel, calculateLogisticsFee, productDecimal, sumDecimal, quoteCnyFromUsd,
    purchaseTaxBlockReason: { value: '' }, normalizedBundleSets: (n: number) => n, quoteMode: { value: mode },
    singleActualWeight: (_p: unknown, n: number) => .208 * n, bundleGoodsWeight: (n: number) => .208 * n,
    bundlePurchaseCost: (n: number) => 10.1 * n, bundleDomesticFreight: (n: number) => n,
    findPurchaseProduct: () => null, purchaseRecords: { value: [] }, selectedGradeCoefficient: () => 1.15,
    taxResult: (_country: string, _provider: string, cny: number) => ({ totalUsd: roundQuoteUsd(cny / 7) }),
    exchange: { value: { usd: 7 } }, selectedQuoteRegions: { value: {} }, toast: () => {},
  }
  const run = new Function(...Object.keys(context), js + ';return {quantityCostBreakdown,useLogistics}')(...Object.values(context))
  const p = { purchase: 10.1, purchaseFreightPerUnit: 1, logisticsAttribute: '普货' }
  const quote = (id: number, count: number) => run.quantityCostBreakdown(p, '同名渠道', count, '美国', '燕文', '', `${id}::燕文::DEEP-${id - 9100}`)
  expect(quote(9100, 1).quoteUsd).toBe(4.20)
  expect(quote(9101, 1).quoteUsd).toBe(4.55)
  expect(quote(9100, 5)).not.toBeNull()
  expect(quote(9101, 5)).toBeNull()
  expect(logisticsRuleForChannel('同名渠道', '9100::燕文::DEEP-1')).toBeUndefined()
  expect(logisticsRuleForChannel('同名渠道', '9999::燕文::DEEP-0')).toBeUndefined()
  run.useLogistics(p, { country: '美国', rule: '同名渠道', carrier: '燕文', freight: 16.36, channelKey: '9101::燕文::DEEP-1' })
  expect(p).toMatchObject({ selectedChannelKey: '9101::燕文::DEEP-1', freight: 16.36 })
})

