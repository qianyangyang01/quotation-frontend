import type { BatchSingleLine } from './batchSingleQuotation'
import { ApiError } from './http'

export type BatchQueryState = 'pending' | 'ready' | 'incomplete' | 'missing' | 'failed' | 'invalid'
export const batchQueryLabels: Record<BatchQueryState, string> = {
  pending: '待查询', ready: '资料齐全，可继续报价', incomplete: '资料不全，待补全',
  missing: '未找到商品', failed: '查询失败，可重试', invalid: '输入有误',
}
export function batchLineState(line: BatchSingleLine): BatchQueryState {
  return line.queryState ?? (line.queried ? 'ready' : line.error ? 'invalid' : 'pending')
}
export function batchQueryFailure(error: unknown): BatchQueryState {
  return error instanceof ApiError && error.status === 404 ? 'missing' : 'failed'
}
export function deferredBatchLines(lines: BatchSingleLine[]) {
  return lines.filter(line => batchLineState(line) !== 'invalid' && (!line.queried || !!line.error || line.deferred))
}
export function batchFollowupText(lines: BatchSingleLine[], skuOnly = false) {
  const pending = deferredBatchLines(lines)
  if (skuOnly) return [...new Set(pending.map(line => line.sku))].join('\n')
  const cell = (value: string) => value.replace(/[\t\r\n]+/g, ' ')
  return ['SKU\t状态\t原因', ...pending.map(line => [line.sku, line.queried && line.error ? '报价待处理' : batchQueryLabels[batchLineState(line)], line.error || (line.deferred ? '本次暂缓报价' : '尚未查询')].map(cell).join('\t'))].join('\n')
}
