export type LastQuotationCustomer = { name: string; selectedCustomerId: string }
const storageKey = (userId: string) => `milano.last-quotation-customer.v1:${encodeURIComponent(userId)}`

export function loadLastQuotationCustomer(userId: string): LastQuotationCustomer | null {
  if (!userId) return null
  try {
    const value = JSON.parse(localStorage.getItem(storageKey(userId)) ?? 'null')
    if (!value || typeof value.name !== 'string' || !value.name.trim() || value.name.length > 120
      || typeof value.selectedCustomerId !== 'string') return null
    return { name: value.name.trim(), selectedCustomerId: value.selectedCustomerId }
  } catch { return null }
}

// Keep only the last successfully submitted customer, never unfinished form edits or fee amounts.
export function rememberQuotationCustomer(userId: string, customer: LastQuotationCustomer): void {
  if (!userId || !customer.name.trim()) return
  try {
    localStorage.setItem(storageKey(userId), JSON.stringify(customer))
  } catch { /* Browser storage failure must not turn a successful quotation into a failed save. */ }
}
