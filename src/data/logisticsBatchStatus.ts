import type { BatchResult } from './logisticsRebuild'

export type BatchReviewStatus = 'draft' | 'unchanged' | 'blocked' | 'published'
export const batchReviewStatusLabels: Record<BatchReviewStatus, string> = {
  draft: '可发布', unchanged: '无需发布', blocked: '待处理（不可发布）', published: '本批已发布',
}

export function batchReviewStatus(result: BatchResult, versionStatus?: string, justPublished = false, inScope = true): BatchReviewStatus {
  // An unchanged import references an existing published version. Keep its import outcome.
  if (result.status === 'unchanged') return 'unchanged'
  if (justPublished || versionStatus === 'published' || result.status === 'published') return 'published'
  const status = versionStatus || result.status
  return status === 'draft' && result.pricingReady === true && Boolean(result.versionId && result.channelId)
    && !(result.errors || 0) && !result.pendingReasons?.length && inScope ? 'draft' : 'blocked'
}

export function countBatchReviewStatuses(statuses: BatchReviewStatus[]) {
  const counts = { total: statuses.length, draft: 0, unchanged: 0, blocked: 0, published: 0 }
  for (const status of statuses) counts[status]++
  return counts
}
