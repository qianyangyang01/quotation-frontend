// @vitest-environment happy-dom
import { afterEach, expect, it } from 'vitest'
import { createApp, type App } from 'vue'
import { normalizePurchaseRecord } from '@/data/purchaseStore'
import LegacyPurchasePrices from './LegacyPurchasePrices.vue'

let app: App | undefined
afterEach(() => { app?.unmount(); document.body.innerHTML = '' })

it('renders all five legacy tiers with their own price, quantity range and tax calculation', () => {
  const record = normalizePurchaseRecord({
    sku: 'FL2600389ZT', dataSource: 'legacy_2026', legacyPriceMode: 'auto_tax',
    purchasePriceBasis: 'tax_included', sourceQuotedPriceCny: 100,
    purchasePriceCny: 110, taxIncludedPriceCny: 110, taxPoint: .1, minOrderQty: 1,
    tier2MinQty: 10, tier2PriceCny: 90, tier3MinQty: 100, tier3PriceCny: 80,
    tier4MinQty: 500, tier4PriceCny: 70, tier5MinQty: 1000, tier5PriceCny: 60,
  })
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(LegacyPurchasePrices, { record }); app.mount(host)
  const rows = Array.from(host.querySelectorAll('.legacy-purchase-prices > div'))
  expect(rows).toHaveLength(5)
  expect(rows.map(row => row.querySelector('b')?.textContent)).toEqual([
    '未税单价 ¥100.00/件', '未税单价 ¥90.00/件', '未税单价 ¥80.00/件', '未税单价 ¥70.00/件', '未税单价 ¥60.00/件',
  ])
  expect(rows.map(row => row.querySelector('small')?.textContent)).toEqual([
    '第1档 · 1–9件', '第2档 · 10–99件', '第3档 · 100–499件', '第4档 · 500–999件', '第5档 · 1000件起订',
  ])
  expect(rows[3]?.textContent).toContain('含票价 ¥77.00/件')
  expect(rows[4]?.textContent).toContain('含票价 ¥66.00/件')
})
