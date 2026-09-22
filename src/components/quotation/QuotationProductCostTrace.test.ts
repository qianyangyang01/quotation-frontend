// @vitest-environment happy-dom
import { createApp, h, nextTick, reactive } from 'vue'
import { expect, it } from 'vitest'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
import QuotationProductCostTrace from './QuotationProductCostTrace.vue'

it('shows saved costs and updates when a different quotation is opened', async () => {
  const state = reactive({ record: normalizeQuotationRecord({ id: 'a', no: 'QT-A', primarySku: '001', purchaseBaseUnitPriceCny: 10, purchaseUnitPriceCny: 10.6, purchaseInvoiceRatePercent: 6 })! })
  const host = document.createElement('div')
  const app = createApp({ render: () => h(QuotationProductCostTrace, { record: state.record }) })
  app.mount(host)
  try {
    expect(host.textContent).toContain('产品成本快照')
    expect(host.textContent).toContain('10.60')
    expect(host.textContent).toContain('6%')
    expect(host.textContent).toContain('未保存')
    state.record = normalizeQuotationRecord({ id: 'b', no: 'QT-B', primarySku: 'OLD' })!
    await nextTick()
    expect(host.textContent).toContain('OLD')
    expect(host.textContent).not.toContain('10.60')
  } finally { app.unmount() }
})
