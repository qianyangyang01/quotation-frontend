// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('@/services/http', () => ({ api: mocks }))
vi.mock('@/data/authStore', () => ({ currentAuthUser: ref({ account: 'ME' }) }))
import { currentAuthUser } from '@/data/authStore'
import { reviewNotifications as inbox, startReviewNotifications, stopReviewNotifications, refreshReviewNotifications, acknowledgeReview, markAllReviewsRead, unreadReview, openReviewInbox, type ReviewInbox } from './reviewNotifications'
const entry = { recordId: 'quote-1', eventId: 'event-1', reviewVersion: 2, status: 'approved' }
const result = (eventId = 'event-1'): ReviewInbox => ({ items: [{ ...entry, eventId, kind: 'complete', note: '', actorName: '财务', occurredAt: '2026-10-04T12:00:00Z', quoteNo: 'QT1', customerName: '客户', primarySku: 'SKU' }], unread: [{ ...entry, eventId }], counts: { approved: 1 }, total: 1, page: 0, totalPages: 1 })
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve() }
beforeEach(() => { vi.useFakeTimers(); mocks.get.mockReset(); mocks.post.mockReset(); currentAuthUser.value.account = 'ME'; mocks.get.mockResolvedValue(result()); mocks.post.mockResolvedValue(undefined) })
afterEach(() => { stopReviewNotifications(); vi.useRealTimers() })
describe('persistent review inbox', () => {
  it('marks unread events across every page and immediately clears all badges', async () => {
    const all = result(); all.unread = Array.from({ length: 25 }, (_, i) => ({ ...entry, recordId: `q${i}`, eventId: `e${i}` })); all.total=25;all.counts={approved:25};all.totalPages=2
    mocks.get.mockResolvedValue(all);startReviewNotifications('ME');await flush()
    mocks.get.mockReturnValue(new Promise(() => {})); await markAllReviewsRead()
    expect(mocks.post).toHaveBeenCalledWith('/review-notifications/read-batch', { eventIds: all.unread.map(n=>n.eventId) })
    expect(inbox.total).toBe(0);expect(inbox.counts.approved).toBe(0);expect(inbox.markingAll).toBe(false)
  })
  it('bulk read keeps new events arriving while the request is pending', async () => {
    startReviewNotifications('ME');await flush()
    let finish!:()=>void;mocks.post.mockReturnValue(new Promise<void>(resolve=>{finish=resolve}))
    const saving=markAllReviewsRead();await markAllReviewsRead();expect(mocks.post).toHaveBeenCalledTimes(1)
    mocks.get.mockResolvedValue(result('new-event'));await refreshReviewNotifications();finish();await saving;await flush()
    expect(inbox.total).toBe(1);expect(unreadReview('quote-1')?.eventId).toBe('new-event')
    expect(mocks.post.mock.calls[0]![1]).toEqual({eventIds:['event-1']})
  })
  it('bulk failures keep unread messages and allow retry', async () => {
    startReviewNotifications('ME');await flush();mocks.post.mockRejectedValueOnce(new Error('offline'))
    await markAllReviewsRead();expect(inbox.total).toBe(1);expect(inbox.markingAll).toBe(false);expect(inbox.error).toContain('批量标记失败')
    mocks.get.mockReturnValue(new Promise(()=>{}));await markAllReviewsRead();expect(inbox.total).toBe(0)
  })
  it('ignores batch results after changing accounts', async () => {
    startReviewNotifications('ME');await flush();let finish!:()=>void
    mocks.post.mockReturnValue(new Promise<void>(resolve=>{finish=resolve}));const saving=markAllReviewsRead()
    currentAuthUser.value.account='OTHER';mocks.get.mockResolvedValue(result('other-event'));startReviewNotifications('OTHER');await flush()
    finish();await saving;expect(inbox.total).toBe(1);expect(inbox.unread[0]!.eventId).toBe('other-event')
  })
  it('opening or dismissing the inbox does not consume messages', async () => {
    startReviewNotifications('ME'); await flush(); openReviewInbox(); await flush(); inbox.opened = false
    expect(mocks.post).not.toHaveBeenCalled(); expect(inbox.total).toBe(1)
  })
  it('updates all counts and badges immediately without waiting for the next refresh', async () => {
    startReviewNotifications('ME'); await flush()
    mocks.get.mockReturnValue(new Promise(() => {}))
    void acknowledgeReview(entry, 2); await flush()
    expect(mocks.post).toHaveBeenCalledWith('/review-notifications/quote-1/read', { eventId: 'event-1' })
    expect(inbox.total).toBe(0); expect(inbox.counts.approved).toBe(0); expect(unreadReview('quote-1')).toBeUndefined(); expect(inbox.items).toHaveLength(0)
  })
  it('does not acknowledge a result newer than the displayed detail', async () => {
    startReviewNotifications('ME'); await flush(); await acknowledgeReview(entry, 1)
    expect(mocks.post).not.toHaveBeenCalled(); expect(inbox.total).toBe(1)
  })
  it('preserves the unread badge when saving read status fails', async () => {
    startReviewNotifications('ME'); await flush(); mocks.post.mockRejectedValue(new Error('offline'))
    await acknowledgeReview(entry, 2); expect(inbox.total).toBe(1); expect(inbox.error).toContain('保存失败')
  })
  it('does not clear a newer message when an older acknowledgement finishes', async () => {
    startReviewNotifications('ME'); await flush()
    let saved!: () => void; mocks.post.mockReturnValue(new Promise<void>(resolve => { saved = resolve }))
    void acknowledgeReview(entry, 2)
    mocks.get.mockResolvedValue(result('event-2')); await refreshReviewNotifications(); saved(); await flush()
    expect(unreadReview('quote-1')?.eventId).toBe('event-2'); expect(inbox.total).toBe(1)
  })
  it('ignores responses from a previous account and clears its data immediately', async () => {
    let deliver!: (data: ReviewInbox) => void; mocks.get.mockReturnValueOnce(new Promise<ReviewInbox>(resolve => { deliver = resolve }))
    startReviewNotifications('ME'); currentAuthUser.value.account = 'OTHER'
    mocks.get.mockResolvedValue({ ...result(), items: [], unread: [], counts: {}, total: 0 }); startReviewNotifications('OTHER')
    deliver(result()); await flush(); expect(inbox.total).toBe(0); expect(inbox.account).toBe('OTHER')
  })
  it('announces new events once and coalesces a batch', async () => {
    startReviewNotifications('ME'); await flush(); expect(inbox.announcement).toBe('')
    mocks.get.mockResolvedValue(result('event-2')); await refreshReviewNotifications(); expect(inbox.announcement).toBe('你有 1 条新的审核消息')
    inbox.announcement = ''; await refreshReviewNotifications(); expect(inbox.announcement).toBe('')
  })
})
