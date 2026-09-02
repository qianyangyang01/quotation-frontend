import type { PurchaseImportDuplicateGroup, PurchaseImportJob } from './purchaseAsyncImports'

type DuplicateSelections = Record<string, { sourceSheet: string; sourceRow: number }>

export function purchaseImportConfirmationState(
  job: PurchaseImportJob | null,
  duplicateGroups: PurchaseImportDuplicateGroup[],
  selections: DuplicateSelections,
) {
  const continuation = job?.summary?.continuation?.mode === 'append' ? job.summary.continuation : undefined
  const skuBackfillCount = continuation?.skuBackfillRows ?? 0
  const count = (job?.validRows ?? 0) + duplicateGroups.length
  const duplicatesLoaded = !job?.conflictRows || duplicateGroups.length > 0
  const duplicatesResolved = duplicateGroups.every(group => group.choices.some(choice =>
    choice.sourceSheet === selections[group.sku]?.sourceSheet && choice.sourceRow === selections[group.sku]?.sourceRow,
  ))
  const canReview = job?.status === 'ready' && count > 0 && duplicatesLoaded
    && !continuation?.blocked && continuation?.pendingRows !== 0
  return { continuation, count, skuBackfillCount, canReview, canConfirm: canReview && duplicatesResolved, duplicatesResolved }
}
