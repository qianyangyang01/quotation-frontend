import type { QuotationMatrixRow } from '@/components/quotation/types'

export function hasAnyQuotationPrice(row: QuotationMatrixRow) {
  return row.available !== false && [row.quote1, row.quote2, row.quote3, row.quoteCustom]
    .some(value => typeof value === 'number' && Number.isFinite(value))
}
