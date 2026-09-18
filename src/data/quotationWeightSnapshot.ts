import { decimal, sumDecimal, productDecimal } from '@/services/quotationDecimal'
import { packagingWeightKg } from '@/services/quotationCalculator'

export const PACKAGING_RULE = 'per-item-50g-1g-v1' as const
export const SPECIAL_PACKAGING_ERROR = '特殊包装克重须为 0～100000 的整数，留空表示不增加'
export function parseSpecialPackagingGrams(input: unknown): number | null {
  if (input === '' || input === undefined) return 0
  if (typeof input !== 'number' && typeof input !== 'string') return null
  if (typeof input === 'string' && !/^\d+$/.test(input.trim())) return null
  const n = Number(input)
  return Number.isSafeInteger(n) && n >= 0 && n <= 100000 ? n : null
}
export type QuotationWeightSnapshot = {
  schemaVersion: 1
  rule: typeof PACKAGING_RULE
  specialPackagingGrams: number
  specialPackagingScope: 'per-shipment'
  items: Array<{ sku: string; quantityPerSet: number; baseWeightKg: number; standardPackagingWeightKg: number }>
  quantities: Array<{ quantity: number; baseWeightKg: number; standardPackagingWeightKg: number; specialPackagingWeightKg: number; weightKg: number }>
}
export function buildQuotationWeightSnapshot(items: Array<{sku: string; quantityPerSet: number; baseWeightKg: number}>, specialPackagingGrams: number, quantities: number[]): QuotationWeightSnapshot {
  if (parseSpecialPackagingGrams(specialPackagingGrams) === null) throw new Error(SPECIAL_PACKAGING_ERROR)
  const snapshotItems = items.map(item => {
    if (!Number.isFinite(item.baseWeightKg) || item.baseWeightKg < 0 || !Number.isSafeInteger(item.quantityPerSet) || item.quantityPerSet < 1) throw new Error('商品重量或单套数量不合法')
    return {...item, standardPackagingWeightKg: packagingWeightKg(item.baseWeightKg)}
  })
  const base = snapshotItems.reduce((sum, item) => sumDecimal(sum, productDecimal(item.baseWeightKg, item.quantityPerSet)), 0)
  const standard = snapshotItems.reduce((sum, item) => sumDecimal(sum, productDecimal(item.standardPackagingWeightKg, item.quantityPerSet)), 0)
  const special = decimal(specialPackagingGrams).div(1000).toNumber()
  return {schemaVersion: 1, rule: PACKAGING_RULE, specialPackagingGrams, specialPackagingScope:'per-shipment', items: snapshotItems,
    quantities: [...new Set(quantities)].map(quantity => {
      if (!Number.isSafeInteger(quantity) || quantity < 1) throw new Error('报价数量不合法')
      const baseWeightKg = productDecimal(base, quantity), standardPackagingWeightKg = productDecimal(standard, quantity)
      return {quantity, baseWeightKg, standardPackagingWeightKg, specialPackagingWeightKg:special, weightKg:sumDecimal(baseWeightKg, standardPackagingWeightKg, special)}
    })}
}

/** Never derive missing historical weight fields using today's packaging rule. */
export function normalizeQuotationWeightSnapshot(input: unknown): QuotationWeightSnapshot | undefined {
  if (!input || typeof input !== 'object') return undefined
  const value = input as QuotationWeightSnapshot
  if (value.schemaVersion !== 1 || value.rule !== PACKAGING_RULE || value.specialPackagingScope !== 'per-shipment' || parseSpecialPackagingGrams(value.specialPackagingGrams) === null || !Array.isArray(value.items) || !Array.isArray(value.quantities)) return undefined
  if (!value.items.every(item => [item.baseWeightKg,item.standardPackagingWeightKg,item.quantityPerSet].every(n=>Number.isFinite(n)&&n>=0)) || !value.quantities.every(item=>[item.quantity,item.baseWeightKg,item.standardPackagingWeightKg,item.specialPackagingWeightKg,item.weightKg].every(n=>Number.isFinite(n)&&n>=0))) return undefined
  return structuredClone(value)
}
