/** Independent optional procurement fields; blank is different from an explicit zero. */
export const SPECIAL_INVOICE_COLUMNS = [
  ['含专票价(CNY/件)', 'specialTaxIncludedPriceCny'],
  ['专票点', 'specialTaxPoint'],
  ['专票类型', 'specialInvoiceType'],
] as const

export function validateSpecialInvoice(input: { specialTaxIncludedPriceCny?: unknown; specialTaxPoint?: unknown; specialInvoiceType?: unknown }) {
  for (const [field, label, max] of [
    ['specialTaxIncludedPriceCny', '含专票价', 1_000_000_000], ['specialTaxPoint', '专票点', 1],
  ] as const) {
    const value = input[field]
    if (value == null || value === '') continue
    if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > max)
      throw new Error(`${label}${field === 'specialTaxPoint' ? '须在0至1之间，13%填写0.13' : '须为有效非负数字，且不超过10亿元'}`)
  }
  if (input.specialInvoiceType != null && (typeof input.specialInvoiceType !== 'string' || input.specialInvoiceType.length > 200))
    throw new Error('专票类型须为文字，最多200字')
}
