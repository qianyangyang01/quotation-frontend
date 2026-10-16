import { quotationModeLabels } from './quotationModes'

export const SHIPPING_NOTE_MAX_LENGTH = 200
export const SHIPPING_NOTE_LENGTH_ERROR = '备注信息不能超过200字'

/** Identification only: never parse this free text as a purchase SKU or pricing input. */
export function normalizeShippingNote(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

export function shippingQuotationSummary(value: unknown): string {
  const note = normalizeShippingNote(value)
  return quotationModeLabels['shipping-only'] + (note ? `：${note}` : '')
}
