import { afterEach, expect, it, vi } from 'vitest'
import { buildCustomerQuoteSheet, newQuoteSheetEdits } from '@/data/customerQuoteSheet'
import { copyQuoteSheetData } from './customerQuoteSheetClipboard'
import { copyQuoteSheetImage } from './customerQuoteSheetRenderer'

const sheet = buildCustomerQuoteSheet({ rows: [{ country: 'US', carrier: '4PX', channelKey: 'one', ruleId: 1, rule: '', channelCode: '', transport: '', eta: '5-8 days', quote1: 1, quote2: 2, quote3: 3, quoteCustom: 5 }], countries: [], edits: newQuoteSheetEdits('QA'), customQuantity: 5, bundle: false })
afterEach(() => { vi.unstubAllGlobals(); vi.resetModules() })

it.each(['image', 'data'])('blocks a competing %s copy while another component owns the clipboard, then releases on denial', async kind => {
  let reject!: (error: Error) => void
  const writeText = vi.fn().mockImplementationOnce(() => new Promise<void>((_done, fail) => { reject = fail })).mockResolvedValue(undefined)
  const write = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('isSecureContext', true)
  vi.stubGlobal('navigator', { clipboard: { writeText, write } })
  vi.stubGlobal('ClipboardItem', class {})
  const first = copyQuoteSheetData(sheet).catch(error => error)
  const second = (kind === 'image' ? copyQuoteSheetImage(new Blob()) : copyQuoteSheetData(sheet)).catch(error => error)
  const result = await second
  reject(new Error('denied')); await first
  expect(result).toBeInstanceOf(Error)
  expect(result.message).toContain('正在复制')
  expect(write).not.toHaveBeenCalled()
  expect(writeText).toHaveBeenCalledTimes(1)
  await copyQuoteSheetData(sheet); expect(writeText).toHaveBeenCalledTimes(2)
})

it('coordinates two independent tab modules using a non-queued origin lock and allows retry after completion', async () => {
  let held = false
  const request = vi.fn(async (_name: string, options: { ifAvailable: boolean }, callback: (lock: object | null) => Promise<void>) => {
    expect(options.ifAvailable).toBe(true)
    if (held) return callback(null)
    held = true
    try { return await callback({ name: 'quotation-customer-sheet-clipboard' }) }
    finally { held = false }
  })
  vi.stubGlobal('navigator', { locks: { request } })
  const tabA = await import('./customerQuoteSheetCopyLock')
  vi.resetModules()
  const tabB = await import('./customerQuoteSheetCopyLock')
  let finish!: () => void
  const first = tabA.withQuoteSheetCopyLock(() => new Promise<void>(resolve => { finish = resolve }))
  const secondWrite = vi.fn().mockResolvedValue(undefined)
  await expect(tabB.withQuoteSheetCopyLock(secondWrite)).rejects.toThrow('正在复制')
  expect(secondWrite).not.toHaveBeenCalled()
  finish(); await first
  await tabB.withQuoteSheetCopyLock(secondWrite)
  expect(secondWrite).toHaveBeenCalledTimes(1)
  expect(held).toBe(false)
})
