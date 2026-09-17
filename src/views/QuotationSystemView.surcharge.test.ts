import { applyCommissionThreshold, COMMISSION_THRESHOLD_ERROR } from '@/services/quotationCommission'
import { addCustomerOperationFee } from '@/data/customerOperationFees'
import { buildCustomerQuoteSheet, newQuoteSheetEdits, type QuoteSheetSourceRow } from '@/data/customerQuoteSheet'
import { sumDecimal, productDecimal } from '@/services/quotationDecimal'
import { quoteCnyFromUsd } from '@/services/quotationMoney'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
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
    const context = { applyCommissionThreshold, COMMISSION_THRESHOLD_ERROR, commissionThreshold: { value: '1' }, commissionError: { value: '' }, sumDecimal, productDecimal, addCustomerOperationFee, customerOperation: { value: { configured: true, feeUsd: 0, message: '' } },
      products: { value: [] }, chargeWeight: () => 1,
      purchaseTaxBlockReason: { value: '' },
      quoteCnyFromUsd, calculateFinanceQuoteFees, financeTaxSettings: { value: settings }, financeSurchargeSettings: { value: { ...settings, countries: settings.countries.map(c => ({ ...c, fixedFeeUsd: 2, ...(scoped ? { exemptChannelKeys: ['1::物流商::FREE', '1::豁免商::FREE'] } : {}) })), providers: settings.providers.map(p => ({ ...p, mode: p.provider === '豁免商' ? 'exempt' : 'taxable' })) } }, usdPriceFromCny: (cny: number) => cny / 5,
      logisticsRules: [{ id: 1, name: '同一规则', relations: ['PAY', 'FREE'].map(code => ({ carrier: '物流商', channel: '同名渠道', channelCode: code })) }], normalizedBundleSets: (n: number) => n,
      financeChannelKey: (id: number, relation: { carrier: string; channelCode: string }) => id + '::' + relation.carrier + '::' + relation.channelCode,
      salePrice: () => 55,
      logisticsRuleForChannel: () => ({ id: 1, name: '同一规则', relations: [{ carrier: '物流商', channelCode: 'PAY' }, { carrier: '豁免商', channelCode: 'FREE' }] }),
      navigator: { clipboard: { writeText: async (text: string) => { copied = text } } }, toast: () => {},
      quoteMode: { value: mode }, bundleGoodsWeight: (n: number) => n, singleActualWeight: (_p: unknown, n: number) => n,
      calculateLogisticsFee: (_rule: unknown, _country: string, weightKg: number) => ({ total: 10, chargeWeightKg: weightKg }), quoteRegionForCountry: () => '',
      bundlePurchaseCost: (n: number) => n * 40, bundleDomesticFreight: (n: number) => n * 5,
      findPurchaseProduct: () => null, purchaseRecords: { value: [] }, purchasePriceForMonthlySales: () => 40,
      selectedGradeCoefficient: () => 1, exchange: { value: { usd: 5 } }, customQuoteQuantity: { value: 10 },
      matchedLogistics: () => ['PAY', 'PAY2', 'FREE'].map(code => ({ rule: '同一规则', carrier: code === 'FREE' ? '豁免商' : '物流商', channel: '同名渠道', channelKey: '1::物流商::' + code })),
    }
    const run = new Function(...Object.keys(context), js + '\nreturn {excelQuoteRows, finalSalePrice, copySpecifiedQuotes, quantityCostBreakdown}')(...Object.values(context))
    const product = { country: '美国', quantity: 1, purchase: 40, purchaseFreightPerUnit: 5, sku: 'sku', rule: '同一规则', channel: '物流商' }
    const rows = run.excelQuoteRows(product)
    const paid = rows.find((row: { channelKey: string }) => row.channelKey.endsWith('PAY'))
    const free = rows.find((row: { channelKey: string }) => row.channelKey.endsWith('FREE'))
    expect(paid).toMatchObject({ quote1: 18, quote2: 27, quote3: 36, quoteCustom: 99, surchargeUsd: 2, quoteCny: 495 })
    expect(free).toMatchObject({ quote1: 16, quote2: 25, quote3: 34, quoteCustom: 97, surchargeUsd: 0, surchargeExempt: true })
    expect(rows[0].channelKey).toBe('1::物流商::FREE')
    context.customerOperation.value.feeUsd = 1.25
    const adjusted = run.excelQuoteRows(product)
    for (const before of rows) {
      const after = adjusted.find((row: { channelKey: string }) => row.channelKey === before.channelKey)
      for (const key of ['quote1', 'quote2', 'quote3', 'quoteCustom']) expect(after[key]).toBe(before[key] + 1.25)
      expect(after.totalCostCny).toBe(before.totalCostCny)
    }
    context.commissionThreshold.value = '0.95'
    const commissioned = run.excelQuoteRows(product)
    for (const before of adjusted) {
      const after = commissioned.find((row: { channelKey: string }) => row.channelKey === before.channelKey)
      for (const key of ['quote1', 'quote2', 'quote3', 'quoteCustom']) expect(after[key]).toBe(applyCommissionThreshold(before[key], .95))
      expect(after.totalCostCny).toBe(before.totalCostCny)
      expect(after.quoteCny).toBe(quoteCnyFromUsd(after.quoteCustom, 5))
    }
    expect(run.excelQuoteRows(product)).toEqual(commissioned)
    context.commissionThreshold.value = ''
    const invalid = run.excelQuoteRows(product)
    expect(invalid.every((row: {quote1: number|null; taxConfigured: boolean}) => row.quote1 === null && !row.taxConfigured)).toBe(true)
    context.commissionError.value = COMMISSION_THRESHOLD_ERROR
    await run.copySpecifiedQuotes(commissioned)
    expect(copied).toBe('')
    context.commissionError.value = ''
    context.commissionThreshold.value = '1'
    context.customerOperation.value.feeUsd = 0
    expect(run.excelQuoteRows(product)).toEqual(rows)
    expect(run.finalSalePrice({ ...product, selectedChannelKey: '1::物流商::PAY' })).toBe(90)
    expect(run.finalSalePrice({ ...product, channel: '豁免商' })).toBe(80)
    expect(rows.find((r: {channelKey: string}) => r.channelKey.endsWith('PAY2')).quoteCustom).toBe(99)
    await run.copySpecifiedQuotes(rows)
    const table = copied.split('\r\n').map(row => row.split('\t'))
    const surchargeColumn = table[0]!.indexOf('附加费（USD/单）')
    expect(table[1]![surchargeColumn]).toBe('0.00')
    expect(table[2]![surchargeColumn]).toBe('2.00')
    expect(table[2]!.slice(-2)).toEqual(['99.00', '495.00'])
    // Added sheet columns execute the same live quantity function, including per-order fees.
    const original = JSON.stringify(rows)
    const quantities = [1,2,3,4,5,6,7,8,9,10]
    const sheet = buildCustomerQuoteSheet({ rows, countries: [], edits: newQuoteSheetEdits('QA'), customQuantity: 10, bundle: mode === 'bundle', quantities,
      calculatePrice: (row: QuoteSheetSourceRow, quantity: number) => run.quantityCostBreakdown(product, row.rule, quantity, row.country, row.carrier, row.quoteRegion || '', row.channelKey)?.quoteUsd ?? null })
    const paidSheet = sheet.rows.find(row => row.key.includes('::PAY"'))!
    const freeSheet = sheet.rows.find(row => row.key.includes('::FREE"'))!
    expect(paidSheet.prices).toEqual(quantities.map(quantity => 9 * quantity + 9))
    expect(freeSheet.prices).toEqual(quantities.map(quantity => 9 * quantity + 7))
    expect(JSON.stringify(rows)).toBe(original)
    context.customerOperation.value.feeUsd = 1.25
    const adjustedSheet = buildCustomerQuoteSheet({ rows: adjusted, countries: [], edits: newQuoteSheetEdits('QA'), customQuantity: 10, bundle: mode === 'bundle', quantities,
      calculatePrice: (row: QuoteSheetSourceRow, quantity: number) => run.quantityCostBreakdown(product, row.rule, quantity, row.country, row.carrier, row.quoteRegion || '', row.channelKey)?.quoteUsd ?? null })
    expect(adjustedSheet.rows.find(row => row.key.includes('::PAY"'))!.prices).toEqual(quantities.map(quantity => 9 * quantity + 10.25))
    expect(adjustedSheet.rows.find(row => row.key.includes('::FREE"'))!.prices).toEqual(quantities.map(quantity => 9 * quantity + 8.25))
    await run.copySpecifiedQuotes(adjusted)
    expect(copied.split('\r\n')[2]!.split('\t').slice(-2)).toEqual(['100.25', '501.25'])

    // Channel duty replaces the country default, while surcharge and customer labor remain separate.
    context.customerOperation.value.feeUsd = 0
    settings.countries[0]!.channelRules = [{key:'1::物流商::PAY',mode:'fixed-order',amount:0.3,perKg:0,currency:'USD'}]
    const channelPriced = run.excelQuoteRows(product).find((row: {channelKey:string}) => row.channelKey === '1::物流商::PAY')
    expect(channelPriced).toMatchObject({quote1:13.3,quote2:22.3,quote3:31.3,quoteCustom:94.3,taxFeeMode:'fixed-order',surchargeUsd:2})
    expect(channelPriced.taxCalculations['10']).toMatchObject({rule:'channel-tax-v1',taxUsd:0.3})
    settings.countries[0]!.channelRules[0]!.mode = 'unavailable'
    const unavailable = run.excelQuoteRows(product).find((row: {channelKey:string}) => row.channelKey === '1::物流商::PAY')
    expect(unavailable).toMatchObject({quote1:null,quote2:null,quote3:null,quoteCustom:null,taxConfigured:false})
    expect(JSON.stringify(unavailable.quantityMessages)).toContain('该渠道不支持当前国家')

    // Romania adds its own processing fee to EU duty in both single and bundle quantity paths.
    settings.countries.push(
      { country: '欧盟', selected: true, enabled: true, fixedFeeUsd: 3, sortOrder: 2 },
      { country: '罗马尼亚', selected: true, enabled: true, fixedFeeUsd: 7, sortOrder: 3, euTaxMode: 'add-handling', handlingRules: [{ key: '1::物流商::PAY', mode: 'fixed-order', amount: 1, perKg: 0, currency: 'USD' }] },
    )
    const romaniaRows = run.excelQuoteRows({ ...product, country: '罗马尼亚' })
    const romania = romaniaRows.find((row: { channelKey: string }) => row.channelKey === '1::物流商::PAY')
    expect(romania).toMatchObject({ quote1: 15, quote2: 24, quote3: 33, quoteCustom: 96, surchargeUsd: 0 })
    expect(romania.taxCalculations['10']).toMatchObject({ rule: 'eu-handling-v1', euTaxUsd: 3, handlingFeeUsd: 1, taxUsd: 4 })
    const romaniaSheet = buildCustomerQuoteSheet({ rows: romaniaRows, countries: [], edits: newQuoteSheetEdits('QA'), customQuantity: 10, bundle: mode === 'bundle', quantities,
      calculatePrice: (row: QuoteSheetSourceRow, quantity: number) => run.quantityCostBreakdown({ ...product, country: '罗马尼亚' }, row.rule, quantity, row.country, row.carrier, '', row.channelKey)?.quoteUsd ?? null })
    expect(romaniaSheet.rows.find(row => row.key.includes('::PAY"'))!.prices).toEqual(quantities.map(quantity => 9 * quantity + 6))

  })
})

it('blocks calculation and copying immediately when purchase tax points are missing', async () => {
  let blocked = 0
  const run = new Function('purchaseTaxBlockReason', 'toast', 'nextTick', js + '\nreturn {excelQuoteRows, quantityCostBreakdown, copySpecifiedQuotes, attemptSave, save, useLogistics}')(
    { value: '该商品采购票点为空，请补齐后报价' }, () => { blocked++ }, nextTick)
  expect(run.excelQuoteRows({ country: '加拿大' }, '加拿大', '2区')).toEqual([])
  expect(run.quantityCostBreakdown({ country: '加拿大' }, '渠道', 1, '加拿大', '物流商', '2区')).toBeNull()
  await run.copySpecifiedQuotes([{ country: '加拿大', quote1: 100 }])
  await run.attemptSave()
  await run.save()
  run.useLogistics({}, {})
  expect(blocked).toBe(4)
})

it('blocks direct save and copy for an invalid commission before touching a record', async () => {
  let blocked = 0
  const run = new Function('purchaseTaxBlockReason', 'commissionError', 'toast', 'nextTick', js + '\nreturn {save, copySpecifiedQuotes}')(
    { value: '' }, { value: COMMISSION_THRESHOLD_ERROR }, () => { blocked++ }, nextTick)
  await run.save()
  await run.copySpecifiedQuotes([{quote1:6}])
  expect(blocked).toBe(2)
})
