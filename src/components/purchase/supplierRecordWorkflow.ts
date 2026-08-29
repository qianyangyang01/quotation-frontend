export function createLatestRequestGuard() {
  let latest = 0
  return {
    begin() { latest += 1; return latest },
    isLatest(request: number) { return request === latest },
    invalidate() { latest += 1 },
  }
}

export function shouldPersistSupplierBase(recordExists: boolean, draft: unknown, savedSnapshot: string) {
  return !recordExists || JSON.stringify(draft) !== savedSnapshot
}

export function shouldWarnSupplierUnload(hasUnsavedChanges: boolean, saving: boolean, deleting: boolean) {
  return hasUnsavedChanges && !saving && !deleting
}
