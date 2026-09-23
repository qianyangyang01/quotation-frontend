// @vitest-environment happy-dom
import { createApp, h } from 'vue'
import { expect, it } from 'vitest'
import QuoteTaxMeta from './QuoteTaxMeta.vue'
import type { QuotationMatrixRow } from './types'
import { calculateFinanceQuoteTax, normalizeFinanceTaxSettings } from '@/data/financeTaxSettings'

function badge(row: Partial<QuotationMatrixRow>) {
  const host = document.createElement('div')
  const app = createApp({ render: () => h(QuoteTaxMeta, { row: row as QuotationMatrixRow }) })
  app.mount(host)
  try { return host.textContent } finally { app.unmount() }
}

const channels = [
  '592::云途::C-600c364a09421e97a32f',
  '593::云途::C-f79790fa71c225481346',
  '594::云途::C-12d8524ab88e7866389a',
]
const settings = normalizeFinanceTaxSettings({ countries: [{
  country: '欧盟', selected: true, enabled: true, fixedFeeUsd: 3.51, sortOrder: 1,
  channelRules: channels.map(key => ({ key, mode: 'weight', amount: .6, perKg: 1.5, currency: 'EUR' })),
}] })

it.each(channels)('shows calculated weight duty instead of the zero fixed-fee field for %s', channelKey => {
  for (const [weightKg, expected] of [[.215, 1.07], [.43, 1.44], [.645, 1.82]] as const) {
    const tax = calculateFinanceQuoteTax(settings, '法国', '云途', 10, { channelKey, weightKg, eurUsd: 1.16 })
    expect(tax).toMatchObject({ configured: true, feeMode: 'weight-order', fixedFeeUsd: 0, taxUsd: expected, totalUsd: 10 + expected })
    expect(badge({ taxFeeMode: tax.feeMode, taxIncluded: tax.included, countryFixedTaxUsd: tax.fixedFeeUsd, taxLabel: tax.label }))
      .toBe(`关税 $${expected.toFixed(2)}/单（按整单重量）`)
  }
})

it.each([
  [{ taxFeeMode: 'fixed-order', countryFixedTaxUsd: 3.51 }, '关税 $3.51/单'],
  [{ taxFeeMode: 'fixed-order', countryFixedTaxUsd: 0 }, '关税 $0.00/单'],
  [{ taxFeeMode: 'weight-eur', taxLabel: '关税 $1.07/单（1件 · 计费重 215g）' }, '关税 $1.07/单（1件 · 计费重 215g）'],
  [{ taxFeeMode: 'no-tax' }, '无关税'],
  [{ taxFeeMode: 'exempt', taxIncluded: true }, '免税'],
  [{ taxFeeMode: 'missing', taxLabel: '请财务设置欧元兑美元汇率' }, '请财务设置欧元兑美元汇率'],
] as Array<[Partial<QuotationMatrixRow>, string]>)('preserves other duty labels: %j', (row, expected) => {
  expect(badge(row)).toBe(expected)
})

it('keeps separately configured surcharges visible beside weight duty', () => {
  expect(badge({ taxFeeMode: 'weight-order', countryFixedTaxUsd: 0, taxLabel: '关税 $1.07/单（按整单重量）', surchargeEnabled: true, surchargeLabel: '附加费 $0.50/单' }))
    .toBe('关税 $1.07/单（按整单重量）附加费 $0.50/单')
})
