/* Run only in the legacy quotation page DevTools console before production migration. */
(async () => {
  const database = await new Promise((resolve, reject) => {
    const request = indexedDB.open('milano-quotation', 1)
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
  const purchaseProducts = await new Promise((resolve, reject) => {
    const request = database.transaction('purchase-products', 'readonly').objectStore('purchase-products').getAll()
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
  const allowedPrefixes = ['milano.finance-', 'milano.logistics', 'milano.quotation.records', 'milano.quotation.personal-templates']
  const forbiddenFragments = ['auth', 'session', 'draft', 'test', 'mock']
  const candidates = Object.fromEntries(Object.keys(localStorage)
    .filter(key => allowedPrefixes.some(prefix => key.startsWith(prefix)))
    .filter(key => !forbiddenFragments.some(fragment => key.toLowerCase().includes(fragment)))
    .map(key => [key, JSON.parse(localStorage.getItem(key) || 'null')]))
  const exportData = {
    format: 'quotation-browser-authority-v1', exportedAt: new Date().toISOString(),
    exclusions: ['local accounts', 'sessions', 'drafts', 'test/mock keys'],
    requiresWhitelistApproval: true, purchaseProducts, candidates,
  }
  const link = document.createElement('a')
  link.href = URL.createObjectURL(new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' }))
  link.download = `quotation-browser-authority-${new Date().toISOString().slice(0, 10)}.json`
  link.click()
  URL.revokeObjectURL(link.href)
})()
