import { readFinanceSetting, writeFinanceSetting } from '@/services/financeSettings'
import { roundQuoteUsd } from '@/services/quotationMoney'
import { sumDecimal } from '@/services/quotationDecimal'

export type CustomerOperationFee = { id: string; name: string; feeUsd: number; enabled: boolean }
export type CustomerOperationSettings = { customers: CustomerOperationFee[] }
export type CustomerOperationSnapshot = { id: string; name: string; feeUsd: number }
export const CUSTOMER_OPERATION_FEES_UPDATED = 'milano:customer-operation-fees-updated'

export function loadCustomerOperationSettings(): CustomerOperationSettings {
  const stored = readFinanceSetting<CustomerOperationSettings>('customer-operation-fees')
  return { customers: (stored?.customers || []).map(row => ({ ...row })) }
}
export function validateCustomerOperationSettings(settings: CustomerOperationSettings) {
  if (!Array.isArray(settings.customers) || settings.customers.length > 1000) throw new Error('最多维护1000个客户')
  const ids = new Set<string>(), names = new Set<string>()
  for (const row of settings.customers) {
    const name = row.name.trim().toLocaleUpperCase('en-US')
    if (!row.id || ids.has(row.id) || !name || row.name.trim().length > 120 || names.has(name)) throw new Error('客户名称不能为空、超过120字或重复')
    if (typeof row.feeUsd !== 'number' || !Number.isFinite(row.feeUsd) || row.feeUsd < 0 || row.feeUsd > 1_000_000 || !/^\d+(\.\d{1,2})?$/.test(String(row.feeUsd))) throw new Error('操作费须为0～1000000美元，最多两位小数')
    if (typeof row.enabled !== 'boolean') throw new Error('客户启用状态不正确')
    ids.add(row.id); names.add(name)
  }
}
export async function saveCustomerOperationSettings(settings: CustomerOperationSettings) {
  validateCustomerOperationSettings(settings)
  const value = { customers: settings.customers.map(row => ({ ...row, name: row.name.trim() })) }
  const saved = await writeFinanceSetting('customer-operation-fees', value)
  window.dispatchEvent(new CustomEvent(CUSTOMER_OPERATION_FEES_UPDATED))
  return saved
}
// A typed name never resolves a fee. Only an explicit, still-valid selection does.
export function resolveCustomerOperation(settings: CustomerOperationSettings, selectedId: string, name: string) {
  if (!selectedId) return { configured: true, feeUsd: 0, snapshot: undefined as CustomerOperationSnapshot | undefined, message: '' }
  const row = settings.customers.find(row => row.id === selectedId && row.enabled && row.name === name.trim())
  if (!row || !Number.isFinite(row.feeUsd) || row.feeUsd < 0) return { configured: false, feeUsd: 0, snapshot: undefined, message: '所选客户设置已变化，请重新选择客户或改为手动输入' }
  return { configured: true, feeUsd: row.feeUsd, snapshot: { id: row.id, name: row.name, feeUsd: row.feeUsd }, message: `公司操作费 $${row.feeUsd.toFixed(2)}/单` }
}
export function addCustomerOperationFee(originalQuoteUsd: number, feeUsd: number) {
  return roundQuoteUsd(sumDecimal(originalQuoteUsd, feeUsd))
}
