const RUNNING_STATUSES = new Set([
  'queued',
  'parsing',
  'import-queued',
  'importing',
  'rollback-queued',
  'rolling-back',
])

const DATA_CHANGED_STATUSES = new Set(['completed', 'completed-with-errors', 'rolled-back'])

export function shouldPollPurchaseImportJobs(taskCenterOpen: boolean, statuses: Array<string | null | undefined>) {
  return taskCenterOpen && statuses.some(status => status != null && RUNNING_STATUSES.has(status))
}

export function didPurchaseImportDataChange(previousStatus: string | null | undefined, nextStatus: string) {
  return !DATA_CHANGED_STATUSES.has(previousStatus ?? '') && DATA_CHANGED_STATUSES.has(nextStatus)
}
