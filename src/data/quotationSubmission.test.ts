import { afterEach, expect, it, vi } from 'vitest'
import { api } from '@/services/http'
import { quotationSubmitter } from './quotationRecords'

afterEach(() => vi.restoreAllMocks())
const input = { customerName: '客户', salespersonAccount: 'A', primarySku: 'SKU' } as Parameters<ReturnType<typeof quotationSubmitter>>[0]
const saved = { ...input, id: 'saved-1', no: 'QT-1', createdAt: '2026-09-28T00:00:00Z' }

it('reuses the same submission key after a lost response and rotates it after success', async () => {
  const post = vi.spyOn(api, 'post').mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValue(saved)
  const submit = quotationSubmitter()
  await expect(submit(input)).rejects.toThrow('Failed to fetch')
  await submit(structuredClone(input))
  expect(post.mock.calls[1]![2]).toBe(post.mock.calls[0]![2])
  await submit(input)
  expect(post.mock.calls[2]![2]).not.toBe(post.mock.calls[1]![2])
})

it('never reuses a key for a changed payload or another page', async () => {
  const post = vi.spyOn(api, 'post').mockRejectedValue(new Error('offline'))
  const submit = quotationSubmitter()
  await expect(submit(input)).rejects.toThrow()
  await expect(submit({ ...input, customerName: '另一客户' })).rejects.toThrow()
  await expect(quotationSubmitter()(input)).rejects.toThrow()
  expect(new Set(post.mock.calls.map(call => call[2])).size).toBe(3)
})
