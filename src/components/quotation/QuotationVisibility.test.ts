// @vitest-environment happy-dom
import { afterEach, expect, it } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { authState } from '@/data/authStore'
import { setPricingTestPermissions, clearPricingTestPermissions } from '@/test/pricingPermissions'
import CostWeightPanel from './CostWeightPanel.vue'
import BundleProductCard from './BundleProductCard.vue'
import LogisticsPanel from './LogisticsPanel.vue'
import PriceSummary from './PriceSummary.vue'
import QuotationHeader from './QuotationHeader.vue'
import FobRecordDetail from './FobRecordDetail.vue'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
import type { BundleQuoteItem, QuotationProduct } from './types'

let app: App | undefined
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; clearPricingTestPermissions() })
const product = { sku: 'SECRET-SKU', netWeight: .2, quantity: 1, purchase: 731.29, purchaseBaseUnitPrice: 700,
  purchaseFreightPerUnit: 12.37, purchaseInvoiceRatePercent: 6, purchaseInvoiceTaxApplied: true,
  weightSource: 'purchase', freight: 83.46, country: '美国', channel: '专线', rule: '普货' } as QuotationProduct
const bundle = { id: 1, sku: 'SECRET-SKU', name: '商品', quantityPerSet: 1, weightKg: .2, customWeightKg: null,
  purchaseUnitPrice: 731.29, purchaseBaseUnitPrice: 700, purchaseFreightPerUnit: 12.37,
  purchaseInvoiceRatePercent: 6, purchaseInvoiceTaxApplied: true, stockStatus: '有货' } as BundleQuoteItem

function mountWorkspace() {
  const host = document.createElement('div'); document.body.append(host)
  app = createApp({ render: () => h('div', [
    h(QuotationHeader, { salesperson: '业务员', rate: 6.7, status: '已就绪', modeLabel: '单品' }),
    h(CostWeightPanel, { product, baseWeight: .2, packagingWeight: .004, chargeWeight: .204, domesticFreight: 12.37, purchaseTierLabel: '阶梯价1' }),
    h(BundleProductCard, { items: [bundle], purchaseCost: 731.29, baseWeight: .2, packagingWeight: .004, totalWeight: .204, domesticFreight: 12.37 }),
    h(LogisticsPanel, { product, rules: ['普货'], grade: 'S', coefficient: 1.27, exchangeRate: 6.7 }),
    h(PriceSummary, { cnyPrice: 1000, usdPrice: 150, productCost: 731.29, logisticsCost: 83.46, domesticFreightCost: 12.37,
      profit: 172.88, coefficient: 1.27, grade: 'S', status: '已报价' }),
  ]) }); app.mount(host); return host
}

it('hides sensitive DOM values for employees while retaining quantity, tier, weight and sale prices', () => {
  setPricingTestPermissions(['quote', 'myRecords'])
  const before = JSON.stringify([product, bundle]), host = mountWorkspace()
  for (const secret of ['731.29', '12.37', '83.46', '172.88', '1.27', '计入成本单价', '单套采购成本', '国内运费', '计算规则']) expect(host.innerHTML).not.toContain(secret)
  expect(host.textContent).toContain('204 g')
  expect(host.textContent).toContain('$150.00')
  expect(host.querySelector('.purchase-tier')).not.toBeNull()
  expect(host.querySelector('.custom-weight input')).not.toBeNull()
  expect(JSON.stringify([product, bundle])).toBe(before)
})

it('uses effective module permissions and immediately removes fields after permission loss', async () => {
  setPricingTestPermissions()
  const host = mountWorkspace()
  for (const value of ['731.29', '12.37', '83.46', '172.88', '计算规则']) expect(host.innerHTML).toContain(value)
  authState.permissions = ['quote', 'purchase']; await nextTick()
  expect(host.innerHTML).toContain('731.29')
  expect(host.innerHTML).not.toContain('83.46')
  expect(host.innerHTML).not.toContain('172.88')
  expect(host.textContent).not.toContain('计算规则')
  authState.permissions = ['quote', 'logistics']; await nextTick()
  expect(host.innerHTML).toContain('83.46')
  expect(host.innerHTML).not.toContain('731.29')
  authState.current = null; await nextTick()
  expect(host.innerHTML).not.toContain('83.46')
})

it('hides the saved FOB cost table and factors without changing the stored customer sheet', () => {
  setPricingTestPermissions(['quote', 'myRecords'])
  const record = normalizeQuotationRecord({ id: 'fob-private', no: 'FOB-1', quoteMode: 'fob', primarySku: 'PF-1',
    fob: { schemaVersion: 1, displayMode: 'tiers', quantity: 1, rate: 6.7, product: { sku: 'PF-1', category: '商品', source: 'fob', weight: '200', notices: [], parsed: {
      minOrderQty: 1, orderMultiple: 1, freight: { basis: '原表运费731.29', unitFreightCny: 12.37, quantity: 100, totalFreightCny: 1237, estimated: false }, priceTiers: [] } },
      tiers: [{ minQty: 1, maxQty: null, unit: '件', purchaseCny: 731.29, taxIncludedCny: 804.42, freightCny: 12.37, costCny: 816.79, declaredUsd: '150.00', undeclaredUsd: '153.00', thresholdQty: 1 }],
      current: { minQty: 1, maxQty: 1, unit: '件', declaredUsd: '150.00', undeclaredUsd: '153.00' }, policy: { scope: 'single-price', calculation: 'before-coefficient' },
      sheet: { title: 'Saved sheet', agent: '', date: '2026-10-09', quantityLabels: ['With declaration', 'Without declaration'], notes: [], rows: [], issues: [] } } })!
  const before = JSON.stringify(record), host = document.createElement('div')
  app = createApp(FobRecordDetail, { record }); app.mount(host)
  for (const secret of ['731.29', '804.42', '12.37', '816.79', '1.14', '1.1628', '采购价', '成本价', '票点']) expect(host.innerHTML).not.toContain(secret)
  expect(host.textContent).toContain('150.00')
  expect(JSON.stringify(record)).toBe(before)
})
