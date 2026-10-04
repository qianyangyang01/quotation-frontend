import { reactive } from 'vue'
import { api } from '@/services/http'
import { currentAuthUser } from '@/data/authStore'

export interface UnreadReview { recordId: string; eventId: string; reviewVersion: number; status: string }
export interface ReviewMessage extends UnreadReview { kind: string; note: string; actorName: string; occurredAt: string; quoteNo: string; customerName: string; primarySku: string }
export interface ReviewInbox { items: ReviewMessage[]; unread: UnreadReview[]; counts: Record<string, number>; total: number; page: number; totalPages: number }
const empty = (): ReviewInbox => ({ items: [], unread: [], counts: {}, total: 0, page: 0, totalPages: 0 })
export const reviewNotifications = reactive({ ...empty(), account: '', opened: false, loading: false, markingAll: false, error: '', announcement: '' })
let batchGeneration = 0
let generation = 0
let controller: AbortController | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let running = false
let initialized = false

export function unreadReview(recordId: string) {
  if (reviewNotifications.account !== currentAuthUser.value.account) return undefined
  return reviewNotifications.unread.find(item => item.recordId === recordId)
}
export function stopReviewNotifications() {
  batchGeneration++
  running = false; generation++; controller?.abort(); clearTimeout(timer); initialized = false
  Object.assign(reviewNotifications, empty(), { account: '', opened: false, loading: false, markingAll: false, error: '', announcement: '' })
}
export function startReviewNotifications(account: string) {
  stopReviewNotifications()
  if (!account) return
  reviewNotifications.account = account; running = true
  void refreshReviewNotifications()
}
export async function refreshReviewNotifications(page = reviewNotifications.page) {
  clearTimeout(timer)
  if (!running || document.visibilityState === 'hidden') return
  const account = reviewNotifications.account, current = ++generation
  controller?.abort(); const requestController = new AbortController(); controller = requestController
  const timeout = setTimeout(() => requestController.abort(), 15_000)
  const previous = new Set(reviewNotifications.unread.map(item => item.eventId))
  reviewNotifications.loading = true
  try {
    const result = await api.get<ReviewInbox>(`/review-notifications?page=${page}`, { signal: requestController.signal })
    if (generation !== current || currentAuthUser.value.account !== account) return
    const newCount = result.unread.filter(item => !previous.has(item.eventId)).length
    if (initialized && newCount) reviewNotifications.announcement = `你有 ${newCount} 条新的审核消息`
    Object.assign(reviewNotifications, result, { error: '' }); initialized = true
  } catch {
    if (generation === current) reviewNotifications.error = '审核消息同步失败，请重试'
  } finally {
    clearTimeout(timeout)
    if (generation === current) {
      reviewNotifications.loading = false
      if (running) timer = setTimeout(() => void refreshReviewNotifications(), 10_000)
    }
  }
}
function removeReadEvents(events: Set<string>) {
  generation++; controller?.abort()
  reviewNotifications.loading = false
  reviewNotifications.unread = reviewNotifications.unread.filter(item => !events.has(item.eventId))
  reviewNotifications.items = reviewNotifications.items.filter(item => !events.has(item.eventId))
  reviewNotifications.total = reviewNotifications.unread.length
  reviewNotifications.counts = Object.fromEntries(Object.keys(reviewNotifications.counts).map(status => [status, 0]))
  for (const item of reviewNotifications.unread) reviewNotifications.counts[item.status] = (reviewNotifications.counts[item.status] || 0) + 1
  reviewNotifications.totalPages = Math.ceil(reviewNotifications.total / 20)
  reviewNotifications.page = Math.min(reviewNotifications.page, Math.max(0, reviewNotifications.totalPages - 1))
  if (!reviewNotifications.total) reviewNotifications.announcement = ''
}
/** Explicit bulk action: snapshot all pages, preserve events arriving after the click. */
export async function markAllReviewsRead() {
  const account = reviewNotifications.account
  if (reviewNotifications.markingAll || !reviewNotifications.total || !account || account !== currentAuthUser.value.account) return
  const current = ++batchGeneration
  const events = reviewNotifications.unread.map(item => item.eventId)
  reviewNotifications.markingAll = true; reviewNotifications.error = ''
  try {
    for (let offset = 0; offset < events.length; offset += 1000) {
      if (current !== batchGeneration || account !== currentAuthUser.value.account || account !== reviewNotifications.account) return
      const batch = events.slice(offset, offset + 1000)
      await api.post('/review-notifications/read-batch', { eventIds: batch })
      if (current !== batchGeneration || account !== currentAuthUser.value.account || account !== reviewNotifications.account) return
      removeReadEvents(new Set(batch))
    }
    void refreshReviewNotifications(0)
  } catch {
    if (current === batchGeneration && account === reviewNotifications.account) {
      reviewNotifications.error = '批量标记失败，未完成的消息仍保留未读，请重试'
      if (running) timer = setTimeout(() => void refreshReviewNotifications(), 10_000)
    }
  } finally {
    if (current === batchGeneration) reviewNotifications.markingAll = false
  }
}
/** Acknowledge exactly the message whose review version has actually been rendered. */
export async function acknowledgeReview(entry: UnreadReview, displayedVersion: number) {
  const account = reviewNotifications.account
  if (!account || account !== currentAuthUser.value.account || displayedVersion < entry.reviewVersion) return
  try {
    await api.post(`/review-notifications/${encodeURIComponent(entry.recordId)}/read`, { eventId: entry.eventId })
    if (account !== currentAuthUser.value.account || account !== reviewNotifications.account) return
    removeReadEvents(new Set([entry.eventId]))
    await refreshReviewNotifications()
  } catch {
    if (account === reviewNotifications.account) reviewNotifications.error = '已读状态保存失败，消息仍保留未读，请重新查看'
  }
}
export function openReviewInbox() { reviewNotifications.opened = true; reviewNotifications.announcement = ''; void refreshReviewNotifications(0) }
export function reviewMessageTitle(item: ReviewMessage) {
  if (item.kind === 'comment') return '有新的审核意见'
  return item.status === 'rejected' ? '报价存在价格异常' : item.status === 'channel-exempt' ? '报价已确认同渠道免审' : '报价审核通过'
}
