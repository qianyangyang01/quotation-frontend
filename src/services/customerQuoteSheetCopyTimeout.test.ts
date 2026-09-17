import { afterEach, expect, it, vi } from 'vitest'
import { withQuoteSheetCopyLock, QUOTE_SHEET_COPY_TIMEOUT_MS } from './customerQuoteSheetCopyLock'

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

it('bounds a stalled native copy, blocks competing writes until settlement and then retries', async () => {
  vi.useFakeTimers()
  vi.stubGlobal('navigator', {})
  let finish!: () => void
  const result = withQuoteSheetCopyLock(() => new Promise<void>(resolve => { finish = resolve })).catch(error => error)
  await vi.advanceTimersByTimeAsync(QUOTE_SHEET_COPY_TIMEOUT_MS)
  expect(await result).toMatchObject({ message: expect.stringContaining('复制超时') })
  const another = vi.fn().mockResolvedValue(undefined)
  await expect(withQuoteSheetCopyLock(another)).rejects.toThrow('正在复制')
  expect(another).not.toHaveBeenCalled()
  finish(); await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
  await withQuoteSheetCopyLock(another)
  expect(another).toHaveBeenCalledTimes(1)
  expect(vi.getTimerCount()).toBe(0)
})

it('cancels a late Web Lock callback after timeout without clearing the next copy guard', async () => {
  vi.useFakeTimers()
  let late!: (lock: object) => Promise<void>
  let resolveLock!: () => void
  vi.stubGlobal('navigator', { locks: { request: vi.fn((_name, _options, callback) => {
    late = callback
    return new Promise<void>(resolve => { resolveLock = resolve })
  }) } })
  const oldWrite = vi.fn().mockResolvedValue(undefined)
  const result = withQuoteSheetCopyLock(oldWrite).catch(error => error)
  await vi.advanceTimersByTimeAsync(QUOTE_SHEET_COPY_TIMEOUT_MS)
  expect(await result).toBeInstanceOf(Error)
  vi.stubGlobal('navigator', {})
  let finish!: () => void
  const next = withQuoteSheetCopyLock(() => new Promise<void>(resolve => { finish = resolve }))
  await late({}); resolveLock(); await Promise.resolve(); await Promise.resolve()
  expect(oldWrite).not.toHaveBeenCalled()
  await expect(withQuoteSheetCopyLock(vi.fn())).rejects.toThrow('正在复制')
  finish(); await next
  expect(vi.getTimerCount()).toBe(0)
})
