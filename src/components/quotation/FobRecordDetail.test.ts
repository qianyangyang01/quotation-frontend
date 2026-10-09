// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import FobRecordDetail from './FobRecordDetail.vue'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
import { setPricingTestPermissions, clearPricingTestPermissions } from '@/test/pricingPermissions'

vi.mock('./FobQuoteSheet.vue', () => ({ default: { template: '<div data-saved-sheet>已保存客户报价单</div>' } }))
let app: App
function savedRecord() {
  return normalizeQuotationRecord({
    id: 'fob-clarity', no: 'QT-FOB', quoteMode: 'fob', primarySku: 'PF2600274', customerName: 'Ulrich',
    fob: {
      schemaVersion: 1, quantity: 3, rate: 6.7, displayMode: 'quantity',
      policy: { scope: 'single-price', calculation: 'before-coefficient' },
      product: { sku: 'PF2600274', source: 'fob', category: '', weight: '', notices: [], parsed: {
        minOrderQty: 3, orderMultiple: 1, priceTiers: [{ minQty: 3, maxQty: null, unit: '件', unitPriceCny: 3.9 }],
        freight: { quantity: 100, totalFreightCny: 17, unitFreightCny: 0.17, estimated: false, basis: '100件总运费 17 ÷ 100' },
      } },
      tiers: [{ minQty: 3, maxQty: null, unit: '件', purchaseCny: 3.9, taxIncludedCny: 4.29, freightCny: 0.17, costCny: 4.46, declaredUsd: '0.76', undeclaredUsd: '0.77', thresholdQty: 45 }],
      current: { minQty: 3, maxQty: 3, unit: '件', declaredUsd: '0.93', undeclaredUsd: '0.95' },
      sheet: { title: '已保存报价单', agent: '', date: '', quantityLabels: [], rows: [], issues: [], notes: [] },
    },
  })!
}
async function mount(record = savedRecord()) {
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(FobRecordDetail, { record }); app.mount(host); await nextTick()
  return host
}
afterEach(() => { app?.unmount(); document.body.innerHTML = ''; clearPricingTestPermissions() })

it.each([false, true])('keeps permitted costs visible and distinguishes final columns (purchase permission: %s)', async purchase => {
  setPricingTestPermissions(purchase ? ['quote', 'purchase'] : ['quote'])
  const record = savedRecord(), original = JSON.stringify(record)
  const host = await mount(record), table = host.querySelector('table')!
  expect(table.textContent).toContain('本次最终单价 · 3件')
  expect([...table.querySelectorAll('.final-price strong')].map(el => el.textContent)).toEqual(['0.93', '0.95'])
  expect([...table.querySelectorAll('.base-price')].map(el => el.textContent)).toEqual(['0.76', '0.77'])
  expect(table.textContent).toContain('未含小额订单加价')
  expect(host.querySelector('details')).toBeNull()
  expect(table.textContent?.includes('采购价 ¥')).toBe(purchase)
  expect(host.textContent?.includes('100件总运费')).toBe(purchase)
  if (purchase) expect([...table.querySelectorAll('tbody td')].slice(0, 5).map(el => el.textContent)).toEqual(['3+件', '3.9', '4.29', '0.17', '4.46'])
  expect(JSON.stringify(record)).toBe(original)
})

it('uses the stored current quote even when source values would calculate a different price', async () => {
  setPricingTestPermissions(['quote'])
  const record = savedRecord()
  record.fob!.rate = 9
  record.fob!.product.parsed.priceTiers[0]!.unitPriceCny = 100
  const host = await mount(record)
  expect([...host.querySelectorAll('.final-price strong')].map(el => el.textContent)).toEqual(['0.93', '0.95'])
})

it('does not substitute a base price when the saved final quote is absent', async () => {
  setPricingTestPermissions(['quote'])
  const record = savedRecord(); delete record.fob!.current
  const host = await mount(record)
  expect(host.querySelector('.final-price strong')).toBeNull()
  expect(host.querySelector('[role="alert"]')?.textContent).toContain('未保存本次数量的最终单价')
  expect(host.querySelector('[data-saved-sheet]')).not.toBeNull()
  expect([...host.querySelectorAll('.final-price')].map(el => el.textContent)).toEqual(['未保存', '未保存'])
  expect(host.textContent).not.toContain('已含适用的小额订单加价')
})

it('shows one current-quantity result alongside multiple saved base tiers', async () => {
  setPricingTestPermissions(['quote', 'purchase'])
  const record = savedRecord(), first = record.fob!.tiers![0]!
  first.maxQty = 9
  record.fob!.tiers!.push(
    { ...first, minQty: 10, maxQty: 49, declaredUsd: '0.70', undeclaredUsd: '0.72' },
    { ...first, minQty: 50, maxQty: null, declaredUsd: '0.65', undeclaredUsd: '0.66' },
  )
  record.fob!.quantity = 20
  record.fob!.current = { minQty: 20, maxQty: 20, unit: '套', declaredUsd: '0.70', undeclaredUsd: '0.72' }
  const host = await mount(record)
  expect(host.querySelectorAll('tbody tr')).toHaveLength(3)
  expect(host.querySelectorAll('.final-price')).toHaveLength(2)
  expect(host.querySelector('.final-price')?.getAttribute('rowspan')).toBe('3')
  expect(host.textContent).toContain('本次最终单价 · 20套')
  expect(host.textContent).toContain('单位：美元/套')
  expect([...host.querySelectorAll('.final-price strong')].map(el => el.textContent)).toEqual(['0.70', '0.72'])
})

it('preserves saved final prices when base tiers are absent', async () => {
  setPricingTestPermissions(['quote'])
  const record = savedRecord(); delete record.fob!.tiers
  const host = await mount(record)
  expect(host.textContent).toContain('未保存基础价格明细')
  expect([...host.querySelectorAll('.final-price strong')].map(el => el.textContent)).toEqual(['0.93', '0.95'])
})
