/** Keep task detail, duplicate choices and row pages attached to the current selection. */
export function createPurchaseImportTaskRequests() {
  let selectedJobId: string | null = null
  let selectionVersion = 0
  const latest = new Map<string, number>()

  function select(jobId: string) {
    selectedJobId = jobId
    selectionVersion += 1
    latest.clear()
  }

  async function read<T>(channel: string, jobId: string, reader: () => Promise<T>, apply: (value: T) => void) {
    if (selectedJobId !== jobId) return false
    const version = selectionVersion
    const request = (latest.get(channel) ?? 0) + 1
    latest.set(channel, request)
    const current = () => selectedJobId === jobId && selectionVersion === version && latest.get(channel) === request
    try {
      const value = await reader()
      if (!current()) return false
      apply(value)
      return true
    } catch (error) {
      if (!current()) return false
      throw error
    }
  }

  return { select, read }
}
