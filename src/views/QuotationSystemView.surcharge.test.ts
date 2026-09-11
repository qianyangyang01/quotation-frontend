import { sumDecimal, productDecimal } from '@/services/quotationDecimal'
import { quoteCnyFromUsd } from '@/services/quotationMoney'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { describe, expect, it } from 'vitest'
import { type FinanceTaxSettings } from '@/data/financeTaxSettings'
import { calculateFinanceQuoteFees } from '@/data/financeSurchargeSettings'

// Execute the actual view functions with controlled pricing inputs. This catches
// provider-wide surcharge exemptions and fees omitted from any quantity column.
const source = readFileSync(new URL('./QuotationSystemView.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const parsed = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
const names = ['taxResult', 'finalSalePrice', 'quantityCostBreakdown', 'excelQuoteRows', 'copySpecifiedQuotes', 'attemptSave', 'save', 'useLogistics']
const bodies = parsed.statements.filter(node => ts.isFunctionDeclaration(node) && names.includes(node.name?.text || '')).map(node => node.getText(parsed)).join('\n')
const js = ts.transpileModule(bodies, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText

describe('quotation view fee integration', () => {
  it.each([['single', false], ['bundle', false], ['single', true], ['bundle', true]] as const)('includes surcharge in %s quantity totals with country scope %s', async (mode, scoped) => {
    const settings: FinanceTaxSettings = {
      countries: [{ country: '美国', fixedFeeUsd: 5, selected: true, enabled: true, sortOrder: 1 }],
      providers: ['物流商', '豁免商'].map(provider => ({ provider, mode: 'taxable', selected: true, channels: [] })),
      updatedAt: 'test',
    }
    let copied = ''
    const context = { sumDecimal, productDecimal,
      purchaseTaxBlockReason: { value: '' },
      quoteCnyFromUsd, calculateFinanceQuoteFees, financeTaxSettings: { value: settings }, financeSurchargeSettings: { value: { ...settings, countries: settings.countries.map(c => ({ ...c, fixedFeeUsd: 2, ...(scoped ? { exemptChannelKeys: ['1::物流商::FREE', '1::豁免商::FREE'] } : {}) })), providers: settings.providers.map(p => ({ ...p, mode: p.provider === '豁免商' ? 'exempt' : 'taxable' })) } }, usdPriceFromCny: (cny: number) => cny / 5,
      logisticsRules: [{ id: 1, name: '同一规则', relations: ['PAY', 'FREE'].map(code => ({ carrier: '物流商', channel: '同名渠道', channelCode: code })) }], normalizedBundleSets: (n: number) => n,
      financeChannelKey: (id: number, relation: { carrier: string; channelCode: string }) => id + '::' + relation.carrier + '::' + relation.channelCode,
      salePrice: () => 55,
      logisticsRuleByName: () => ({ id: 1, name: '同一规则', relations: [{ carrier: '物流商', channelCode: 'PAY' }, { carrier: '豁免商', channelCode: 'FREE' }] }),
      navigator: { clipboard: { writeText: async (text: string) => { copied = text } } }, toast: () => {},
      quoteMode: { value: mode }, bundleGoodsWeight: (n: number) => n, singleActualWeight: (_p: unknown, n: number) => n,
      calculateLogisticsFee: () => ({ total: 10 }), quoteRegionForCountry: () => '',
      bundlePurchaseCost: (n: number) => n * 40, bundleDomesticFreight: (n: number) => n * 5,
      findPurchaseProduct: () => null, purchaseRecords: { value: [] }, purchasePriceForMonthlySales: () => 40,
      selectedGradeCoefficient: () => 1, exchange: { value: { usd: 5 } }, customQuoteQuantity: { value: 10 },
      matchedLogistics: () => ['PAY', 'PAY2', 'FREE'].map(code => ({ rule: '同一规则', carrier: code === 'FREE' ? '豁免商' : '物流商', channel: '同名渠道', channelKey: '1::物流商::' + code })),
    }
    const run = new Function(...Object.keys(context), js + '\nreturn {excelQuoteRows, finalSalePrice, copySpecifiedQuotes}')(...Object.values(context))
    const product = { country: '美国', quantity: 1, purchase: 40, purchaseFreightPerUnit: 5, sku: 'sku', rule: '同一规则', channel: '物流商' }
    const rows = run.excelQuoteRows(product)
    const paid = rows.find((row: { channelKey: string }) => row.channelKey.endsWith('PAY'))
    const free = rows.find((row: { channelKey: string }) => row.channelKey.endsWith('FREE'))
    expect(paid).toMatchObject({ quote1: 18, quote2: 27, quote3: 36, quoteCustom: 99, surchargeUsd: 2, quoteCny: 495 })
    expect(free).toMatchObject({ quote1: 16, quote2: 25, quote3: 34, quoteCustom: 97, surchargeUsd: 0, surchargeExempt: true })
    expect(rows[0].channelKey).toBe('1::物流商::FREE')
    expect(run.finalSalePrice({ ...product, selectedChannelKey: '1::物流商::PAY' })).toBe(90)
    expect(run.finalSalePrice({ ...product, channel: '豁免商' })).toBe(80)
    expect(rows.find((r: {channelKey: string}) => r.channelKey.endsWith('PAY2')).quoteCustom).toBe(99)
    await run.copySpecifiedQuotes(rows)
    const table = copied.split('\r\n').map(row => row.split('\t'))
    const surchargeColumn = table[0]!.indexOf('附加费（USD/单）')
    expect(table[1]![surchargeColumn]).toBe('0.00')
    expect(table[2]![surchargeColumn]).toBe('2.00')
    expect(table[2]!.slice(-2)).toEqual(['99.00', '495.00'])
  })
})

it('blocks calculation and copying immediately when purchase tax points are missing', async () => {
  let blocked = 0
  const run = new Function('purchaseTaxBlockReason', 'toast', js + '\nreturn {excelQuoteRows, quantityCostBreakdown, copySpecifiedQuotes, attemptSave, save, useLogistics}')(
    { value: '该商品采购票点为空，请补齐后报价' }, () => { blocked++ })
  expect(run.excelQuoteRows({ country: '加拿大' }, '加拿大', '2区')).toEqual([])
  expect(run.quantityCostBreakdown({ country: '加拿大' }, '渠道', 1, '加拿大', '物流商', '2区')).toBeNull()
  await run.copySpecifiedQuotes([{ country: '加拿大', quote1: 100 }])
  await run.attemptSave()
  await run.save()
  run.useLogistics({}, {})
  expect(blocked).toBe(4)
})
