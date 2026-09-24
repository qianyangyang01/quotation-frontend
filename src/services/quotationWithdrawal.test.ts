import { beforeEach, expect, it, vi } from 'vitest'
import { normalizeQuotationRecord } from '@/data/quotationRecords'
import { cancelQuotation, withdrawQuotation, withdrawalBlocked, withdrawalSubmitter } from './quotationWithdrawal'
const post = vi.hoisted(() => vi.fn())
vi.mock('./http', () => ({ api: { post }, idempotencyKey: () => crypto.randomUUID() }))
const record = () => normalizeQuotationRecord({ id: 'id', no: 'QT-1', _version: 7, salespersonAccount: 'ME', primarySku: 'SKU', status: 'pending', financeReviewStatus: 'reviewing' })!
beforeEach(() => post.mockReset())
it('allows an owner to end active review but rejects deals, archived records and other owners', () => {
  expect(withdrawalBlocked(record(), 'ME')).toBe('')
  expect(withdrawalBlocked(record(), 'OTHER')).toContain('自己的')
  expect(withdrawalBlocked({ ...record(), status: 'won' }, 'ME')).toContain('成交')
  expect(withdrawalBlocked({ ...record(), dealQuantity: 1 }, 'ME')).toContain('成交')
  expect(withdrawalBlocked({ ...record(), lifecycleState: 'withdrawn' }, 'ME')).toContain('当前')
})
it('retries cancellation with the same key and protects the linked draft version', async () => {
  post.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({ cancelled: true })
  await expect(cancelQuotation('id', 7, 3)).rejects.toThrow('timeout')
  await cancelQuotation('id', 7, 3)
  expect(post.mock.calls[0]).toEqual(post.mock.calls[1])
  expect(post).toHaveBeenLastCalledWith('/quotations/id/cancel', { _version: 7, draftVersion: 3 }, 'cancel:id:7:3')
})
it('withdraws only editable conditions and preserves the server source identity', async () => {
  const sourceQuote = { id: 'id', no: 'QT-1', version: 8 }
  post.mockResolvedValue({ exists: true, version: 0, payload: { schemaVersion: 2 }, sourceQuote })
  expect((await withdrawQuotation(record())).sourceQuote).toEqual(sourceQuote)
  const body = post.mock.calls[0]![1]
  expect(body._version).toBe(7)
  expect(body.draft.skuSearch).toBe('SKU')
  expect(body.draft.financeReviewStatus).toBeUndefined()
  expect(body.draft.quoteConfirmed).toBeUndefined()
})
it('retries an uncertain resubmission without creating another quotation or key', async () => {
  const submit = withdrawalSubmitter(), source = { id: 'id', no: 'QT-1', version: 8 }
  post.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue(record())
  await expect(submit(source, 2, { customerName: 'A' })).rejects.toThrow('timeout')
  await submit(source, 2, { customerName: 'A' })
  expect(post.mock.calls[0]).toEqual(post.mock.calls[1])
  await submit(source, 3, { customerName: 'B' })
  expect(post.mock.calls[2]![2]).not.toBe(post.mock.calls[1]![2])
  expect(post.mock.calls.every(call => call[0] === '/quotations/id/resubmit')).toBe(true)
})
