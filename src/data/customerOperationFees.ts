import { readFinanceSetting, writeFinanceSetting } from '@/services/financeSettings'
import { roundQuoteUsd } from '@/services/quotationMoney'
import { sumDecimal } from '@/services/quotationDecimal'

export const operationFeeTiers = [
  { key: '1', label: '1件' }, { key: '2', label: '2件' },
  { key: '3', label: '3件' }, { key: 'above3', label: '3件以上' },
] as const
export type OperationFeeTier = typeof operationFeeTiers[number]['key']
export type OperationFeesByQuantity = Record<OperationFeeTier, number>
export type CustomerOperationFee = { id: string; name: string; feeUsd: number; feesByQuantityUsd?: OperationFeesByQuantity; enabled: boolean }
export type CustomerOperationSettings = { customers: CustomerOperationFee[] }
export type CustomerOperationSnapshot = { id: string; name: string; feeUsd: number; feesByQuantityUsd?: OperationFeesByQuantity }
export const CUSTOMER_OPERATION_FEES_UPDATED = 'milano:customer-operation-fees-updated'

export function fixedOperationFees(feeUsd: number): OperationFeesByQuantity {
  return { '1': feeUsd, '2': feeUsd, '3': feeUsd, above3: feeUsd }
}
export function operationFees(row: Pick<CustomerOperationFee, 'feeUsd' | 'feesByQuantityUsd'>): OperationFeesByQuantity {
  // Only absent tiers are legacy. Incomplete/invalid tier data must never fall back.
  return row.feesByQuantityUsd === undefined ? fixedOperationFees(row.feeUsd) : row.feesByQuantityUsd
}
function validMoney(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1_000_000 && /^\d+(\.\d{1,2})?$/.test(String(value))
}
export function validOperationFees(fees: OperationFeesByQuantity): boolean {
  return !!fees && operationFeeTiers.every(tier => validMoney(fees[tier.key]))
}
export function customerOperationFeeForQuantity(snapshot: Pick<CustomerOperationSnapshot, 'feeUsd' | 'feesByQuantityUsd'> | undefined, quantity: number): number | undefined {
  if (!Number.isSafeInteger(quantity) || quantity < 1) return undefined
  if (!snapshot) return 0
  const fees = operationFees(snapshot)
  return validOperationFees(fees) ? fees[quantity > 3 ? 'above3' : String(quantity) as OperationFeeTier] : undefined
}
export function operationFeesLabel(row: Pick<CustomerOperationFee, 'feeUsd' | 'feesByQuantityUsd'>, unit = '件／套') {
  const fees = operationFees(row)
  if (!validOperationFees(fees)) return '操作费配置不完整'
  return `1${unit} $${fees['1'].toFixed(2)} · 2${unit} $${fees['2'].toFixed(2)} · 3${unit} $${fees['3'].toFixed(2)} · 3${unit}以上 $${fees.above3.toFixed(2)}（每单一次）`
}

export function loadCustomerOperationSettings(): CustomerOperationSettings {
  const stored = readFinanceSetting<CustomerOperationSettings>('customer-operation-fees')
  return { customers: (stored?.customers || []).map(row => ({ ...row, feesByQuantityUsd: { ...operationFees(row) } })) }
}
export function validateCustomerOperationSettings(settings: CustomerOperationSettings) {
  if (!Array.isArray(settings.customers) || settings.customers.length > 1000) throw new Error('最多维护1000个客户')
  const ids = new Set<string>(), names = new Set<string>()
  for (const row of settings.customers) {
    const name = row.name.trim().toLocaleUpperCase('en-US')
    if (!row.id || ids.has(row.id) || !name || row.name.trim().length > 120 || names.has(name)) throw new Error('客户名称不能为空、超过120字或重复')
    if (!validOperationFees(operationFees(row))) throw new Error('四档操作费均须填写0～1000000美元，最多两位小数')
    if (typeof row.enabled !== 'boolean') throw new Error('客户启用状态不正确')
    ids.add(row.id); names.add(name)
  }
}
export async function saveCustomerOperationSettings(settings: CustomerOperationSettings) {
  validateCustomerOperationSettings(settings)
  const value = { customers: settings.customers.map(row => ({ ...row, name: row.name.trim(), feeUsd: operationFees(row)['1'], feesByQuantityUsd: { ...operationFees(row) } })) }
  const saved = await writeFinanceSetting('customer-operation-fees', value)
  window.dispatchEvent(new CustomEvent(CUSTOMER_OPERATION_FEES_UPDATED))
  return saved
}
// A typed name never resolves a fee. Only an explicit, still-valid selection does.
export function resolveCustomerOperation(settings: CustomerOperationSettings, selectedId: string, name: string) {
  if (!selectedId) return { configured: true, feeUsd: 0, snapshot: undefined as CustomerOperationSnapshot | undefined, message: '' }
  const row = settings.customers.find(row => row.id === selectedId && row.enabled && row.name === name.trim())
  if (!row || !validOperationFees(operationFees(row))) return { configured: false, feeUsd: 0, snapshot: undefined, message: '所选客户设置已变化，请重新选择客户或改为手动输入' }
  const fees = { ...operationFees(row) }
  return { configured: true, feeUsd: fees['1'], snapshot: { id: row.id, name: row.name, feeUsd: fees['1'], feesByQuantityUsd: fees }, message: `公司操作费：${operationFeesLabel(row)}` }
}
export function addCustomerOperationFee(originalQuoteUsd: number, feeUsd: number) {
  return roundQuoteUsd(sumDecimal(originalQuoteUsd, feeUsd))
}
