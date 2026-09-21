// @vitest-environment happy-dom
import { createApp, nextTick } from 'vue'
import { expect, it } from 'vitest'
import CostWeightPanel from './CostWeightPanel.vue'
import type { QuotationProduct } from './types'
import { singleActualWeight } from '@/services/quotationCalculator'

it('renders the actual 143g charge weight and keeps manual weight in decimal kilograms', async () => {
  const product = { netWeight: 0.14, manualWeight: 0.14, weightSource: 'manual', quantity: 1, purchase: 97.2, purchaseBaseUnitPrice: 90, purchaseInvoiceRatePercent: 8, purchaseInvoiceTaxApplied: true, purchaseFreightPerUnit: 1.5, volumetricEnabled: false } as QuotationProduct
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp(CostWeightPanel, { product, baseWeight: 0.14, packagingWeight: 0.003, chargeWeight: singleActualWeight(product), domesticFreight: 1.5, purchaseTierLabel: '1件参考价' })
  try {
    app.mount(root)
    await nextTick()
    expect(root.textContent).toContain('143 g')
    expect(root.textContent).not.toContain('144 g')
    expect(root.textContent).toContain('基础 140g + 普通包材 3g')
    const input = root.querySelector<HTMLInputElement>('input[inputmode="numeric"]')!
    input.value = '290'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    expect(product.manualWeight).toBe(0.29)
    expect(singleActualWeight(product)).toBe(0.296)
  } finally {
    app.unmount()
    root.remove()
  }
})

it.each([
  { quantity: 1, purchase: 44.712, freight: 3.4, productCost: '44.71', total: '48.11' },
  { quantity: 3, purchase: 44.712, freight: 10.2, productCost: '134.14', total: '144.34' },
  { quantity: 1, purchase: 0.1, freight: 0.2, productCost: '0.10', total: '0.30' },
])('shows product plus domestic cost for $quantity pieces without mutating source costs', async scenario => {
  const product = { quantity:scenario.quantity, purchase:scenario.purchase, purchaseBaseUnitPrice:scenario.purchase, purchaseFreightPerUnit:scenario.freight/scenario.quantity, netWeight:0.6, weightSource:'purchase', purchaseInvoiceTaxApplied:false } as QuotationProduct
  const original = JSON.stringify(product)
  const root = document.createElement('div')
  const app = createApp(CostWeightPanel, { product, baseWeight:0.6, packagingWeight:0.012, chargeWeight:0.612, domesticFreight:scenario.freight, purchaseTierLabel:'1件参考价' })
  try {
    app.mount(root); await nextTick()
    const cards = root.querySelectorAll('.highlights p')
    expect(cards[0].querySelector('span')?.textContent).toBe('总成本价（CNY）')
    expect(cards[0].querySelector('b')?.textContent).toBe('¥'+scenario.total)
    expect(cards[0].textContent).toContain('商品成本 ¥'+scenario.productCost+' + 国内运费 ¥'+scenario.freight.toFixed(2))
    expect(cards[0].textContent).toContain('不含国际运费')
    expect(cards[1].textContent).toContain('含包材重量612 g')
    expect(JSON.stringify(product)).toBe(original)
  } finally { app.unmount() }
})
