import { quotationFunctionContext } from '@/test/quotationFunctionContext'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { expect, it } from 'vitest'
import { calculateLogisticsFee, formatLogisticsEta, type LogisticsRule } from '@/data/logistics'
import { normalizeLogisticsPriceRow } from '@/data/logisticsRepository'
import { priceRegionLabel } from '@/components/quotation/priceRegionLabel'

const source = readFileSync(new URL('./QuotationSystemView.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const parsed = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
const body = parsed.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'matchedLogistics')!.getText(parsed)
const js = ts.transpileModule(body, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText

it('carries the actual published tariff scope to display without changing AU 2/3 billing', () => {
  const nationwide = normalizeLogisticsPriceRow({ areaName: '澳大利亚', countryCode: 'AU', weightFromKg: 0, weightToKg: 2, pricePerKg: 89, registrationFee: 26, minChargeWeightKg: .01 })
  const make = (id: number, prices: typeof nationwide[]): LogisticsRule => ({ id, name: String(id), status: '启用', billingVerified: true,
    relations: [{ carrier: id === 1 ? '通邮' : '分区渠道', channel: String(id), channelCode: String(id), discounts: '' }], prices } as LogisticsRule)
  const rules = [make(1, [nationwide]), make(2, [
    { ...nationwide, zoneName: '澳大利亚2区', pricePerKg: 100 },
    { ...nationwide, zoneName: '澳大利亚3区', pricePerKg: 120 },
  ])]
  const baseline = JSON.stringify(rules)
  const context = { ...quotationFunctionContext, financeAllowedChannelKeys: () => new Set(['1', '2']), financePolicies: { value: [] }, logisticsRules: rules,
    financeChannelKey: (id: number) => String(id), quoteMode: { value: 'single' }, singleActualWeight: () => .5,
    calculateLogisticsFee, formatLogisticsEta, displayGrams: (kg: number) => kg * 1000 }
  const matched = new Function(...Object.keys(context), js + '\nreturn matchedLogistics')(...Object.values(context))
  for (const zone of [2, 3]) {
    const quoteRegion = `澳大利亚${zone}区`
    const options = matched({ logisticsAttribute: '普货' }, '澳大利亚', quoteRegion)
    expect(options[0]).toMatchObject({ ruleId: 1, freight: 70.5, priceRegion: '' })
    expect(priceRegionLabel({ ...options[0], country: '澳大利亚', quoteRegion })).toBe('全国统一价')
    expect(options[1]).toMatchObject({ ruleId: 2, freight: zone === 2 ? 76 : 86, priceRegion: quoteRegion })
    expect(priceRegionLabel({ ...options[1], country: '澳大利亚', quoteRegion })).toBe(quoteRegion)
  }
  expect(JSON.stringify(rules)).toBe(baseline)
})
