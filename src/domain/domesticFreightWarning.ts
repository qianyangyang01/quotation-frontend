import { purchaseQuoteFreight, type PurchaseProductRecord } from '@/data/purchaseStore'
import { decimal } from '@/services/quotationDecimal'
import { resolveFactoryRegion } from './factoryRegion'

export interface DomesticFreightWarning {
  regionLabel: string
  quantity: number
  weightG: number
  chargeableKg: number
  rate?: 1.5 | 3
  actualTotal: number
  actualUnit: number
  expectedUnit: number
  excessUnit: number
}

function originalWeightConflicts(original: string | undefined, weightG: number) {
  if (!original) return false
  const match = original.normalize('NFKC').trim().match(/^([0-9]+(?:\.[0-9]+)?)\s*(?:kg|千克|公斤)$/i)
  return !!match && !decimal(match[1]!).times(1000).equals(weightG)
}

/** Optional notice only: never changes procurement values or quotation eligibility. */
export function getDomesticFreightWarning(record: PurchaseProductRecord, purchaseTier = '10'): DomesticFreightWarning | null {
  try {
    if (!record || record.freeShipping === '是' || typeof record.weightG !== 'number' || !Number.isFinite(record.weightG) || record.weightG <= 0) return null
    if (originalWeightConflicts(record.weightOriginal, record.weightG)) return null
    const selected = purchaseQuoteFreight(record, purchaseTier).choice
    if (!selected || ![1, 10, 100].includes(selected.quantity) || !Number.isFinite(selected.totalFreightCny) || selected.totalFreightCny < 0) return null
    const region = resolveFactoryRegion(record.factoryInfo)
    // Compare the allocated per-item cost with the single-item shipping rule.
    // The source freight tier determines allocation, never billable weight.
    const chargeable = decimal(record.weightG).div(1000).ceil()
    const rate = region.continuationRateCny
    if (chargeable.greaterThan(1) && rate == null) return null
    const expected = decimal(5).plus(chargeable.minus(1).times(rate ?? 0))
    const actual = decimal(selected.totalFreightCny).div(selected.quantity)
    if (!actual.greaterThan(expected.times('1.5'))) return null
    const excess = actual.minus(expected)
    const warning: DomesticFreightWarning = {
      regionLabel: region.regionLabel,
      quantity: selected.quantity,
      weightG: record.weightG,
      chargeableKg: chargeable.toNumber(),
      ...(rate == null ? {} : { rate }),
      actualTotal: selected.totalFreightCny,
      actualUnit: actual.toNumber(),
      expectedUnit: expected.toNumber(),
      excessUnit: excess.toNumber(),
    }
    return Object.values(warning).some(value => typeof value === 'number' && !Number.isFinite(value)) ? null : warning
  } catch {
    // Malformed optional source data must not interrupt querying or saving a quote.
    return null
  }
}
