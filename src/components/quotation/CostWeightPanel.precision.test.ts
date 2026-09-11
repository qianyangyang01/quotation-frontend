// @vitest-environment happy-dom
import { createApp, nextTick } from 'vue'
import { expect, it } from 'vitest'
import CostWeightPanel from './CostWeightPanel.vue'
import type { QuotationProduct } from './types'
import { singleActualWeight } from '@/services/quotationCalculator'

it('renders the actual 146g charge weight and keeps manual weight in decimal kilograms', async () => {
  const product = { netWeight: 0.14, manualWeight: 0.14, weightSource: 'manual', quantity: 1, purchase: 97.2, purchaseBaseUnitPrice: 90, purchaseInvoiceRatePercent: 8, purchaseInvoiceTaxApplied: true, purchaseFreightPerUnit: 1.5, volumetricEnabled: false } as QuotationProduct
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp(CostWeightPanel, { product, baseWeight: 0.14, packagingWeight: 0.006, chargeWeight: singleActualWeight(product), domesticFreight: 1.5, purchaseTierLabel: '1件参考价' })
  try {
    app.mount(root)
    await nextTick()
    expect(root.textContent).toContain('146 g')
    expect(root.textContent).not.toContain('147 g')
    expect(root.textContent).toContain('基础 140g + 包材 6g')
    const input = root.querySelector<HTMLInputElement>('input[inputmode="numeric"]')!
    input.value = '290'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    expect(product.manualWeight).toBe(0.29)
    expect(singleActualWeight(product)).toBe(0.302)
  } finally {
    app.unmount()
    root.remove()
  }
})
