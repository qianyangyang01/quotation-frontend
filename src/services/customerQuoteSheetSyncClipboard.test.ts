// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { copyQuoteSheetData, copyQuotationText } from './customerQuoteSheetClipboard'
import { buildCustomerQuoteSheet, newQuoteSheetEdits } from '@/data/customerQuoteSheet'

const sheet = () => buildCustomerQuoteSheet({
  rows: [{ country: 'US', carrier: '4PX', channelKey: 'qc', channelCode: 'qc', ruleId: 1, rule: '', transport: '', eta: '7-12 days', quote1: 25.4, quote2: 48.55, quote3: 71.75, quoteCustom: 118.05 }],
  countries: [], edits: { ...newQuoteSheetEdits('QA'), hiddenColumns: ['shippingTime', 'processingTime'] },
  customQuantity: 5, bundle: true, skus: ['KJ2600787', 'YT2601676'],
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); document.body.innerHTML = ''; delete (document as any).execCommand })

it('uses an observed synchronous copy event when the native API is unavailable', async () => {
  vi.stubGlobal('isSecureContext', true)
  const request = vi.fn(), data = new DataTransfer()
  vi.stubGlobal('navigator', { locks: { request } })
  const button = document.createElement('button'); document.body.append(button); button.focus()
  let copied = ''
  const exec = vi.fn((command: string) => {
    expect(command).toBe('copy')
    const field = document.activeElement as HTMLTextAreaElement
    field.dispatchEvent(new ClipboardEvent('copy', { clipboardData: data, bubbles: true, cancelable: true }))
    copied = data.getData('text/plain')
    return true
  })
  Object.defineProperty(document, 'execCommand', { configurable: true, value: exec })
  await copyQuoteSheetData(sheet())
  expect(copied).toBe('No.\tSKU\t1 set (USD)\t2 sets (USD)\t3 sets (USD)\t5 sets (USD)\tCountry / Zone\tLogistics Provider\r\n1\tKJ2600787+YT2601676\t$25.40\t$48.55\t$71.75\t$118.05\tUnited States\t4PX')
  expect(request).not.toHaveBeenCalled()
  expect(document.activeElement).toBe(button); expect(document.querySelector('textarea')).toBeNull()
})

it('falls back inside an open record dialog only after the native write is rejected', async () => {
  vi.stubGlobal('isSecureContext', true)
  const writeText = vi.fn().mockRejectedValue(new Error('denied')), data = new DataTransfer()
  vi.stubGlobal('navigator', { clipboard: { writeText } })
  const dialog = document.createElement('dialog'); dialog.open = true; document.body.append(dialog)
  Object.defineProperty(document, 'execCommand', { configurable: true, value: () => {
    expect(dialog.contains(document.activeElement)).toBe(true)
    document.activeElement!.dispatchEvent(new ClipboardEvent('copy', { clipboardData: data, bubbles: true, cancelable: true }))
    return true
  } })
  await copyQuoteSheetData(sheet())
  expect(writeText).toHaveBeenCalledTimes(1)
  expect(document.querySelector('textarea')).toBeNull()
})

it('does not claim success when both clipboard methods refuse the write', async () => {
  vi.stubGlobal('isSecureContext', true)
  vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
  Object.defineProperty(document, 'execCommand', { configurable: true, value: () => false })
  await expect(copyQuoteSheetData(sheet())).rejects.toThrow('未复制成功')
  expect(document.querySelector('textarea')).toBeNull()
})

it('writes styled HTML and matching plain text in the same synchronous copy event', async () => {
  vi.stubGlobal('isSecureContext', true)
  const data = new DataTransfer()
  vi.stubGlobal('navigator', {})
  const button = document.createElement('button'); document.body.append(button); button.focus()
  Object.defineProperty(document, 'execCommand', { configurable: true, value: () => {
    const event = new ClipboardEvent('copy', { clipboardData: data, bubbles: true, cancelable: true })
    document.activeElement!.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    return true
  } })
  await copyQuotationText('数量\t运费\n1套\t55.78', '<table><tr><td>55.78</td></tr></table>')
  expect(data.getData('text/plain')).toBe('数量\t运费\n1套\t55.78')
  expect(data.getData('text/html')).toContain('<table>')
  expect(document.activeElement).toBe(button)
  expect(document.querySelector('textarea')).toBeNull()
})


it('prefers native HTML and text over a legacy command that falsely reports success', async () => {
  vi.stubGlobal('isSecureContext', true)
  vi.stubGlobal('ClipboardItem', class { constructor(public data: Record<string, Blob>) {} })
  let finish!: () => void
  const write = vi.fn((_items: Array<{ data: Record<string, Blob> }>) => new Promise<void>(resolve => { finish = resolve })), exec = vi.fn(() => true)
  vi.stubGlobal('navigator', { clipboard: { write } })
  Object.defineProperty(document, 'execCommand', { configurable: true, value: exec })
  let complete = false
  const copying = copyQuotationText('SKU\t1件\nTEST\t5.65', '<table><tr><td>5.65</td></tr></table>').then(() => { complete = true })
  await Promise.resolve()
  expect(complete).toBe(false)
  expect(exec).not.toHaveBeenCalled()
  const item = write.mock.calls[0]![0][0]
  expect(await item.data['text/plain'].text()).toContain('TEST\t5.65')
  expect(await item.data['text/html'].text()).toContain('<table>')
  finish(); await copying
  expect(complete).toBe(true)
})

it('rejects a legacy success flag without a clipboard event after native rejection', async () => {
  vi.stubGlobal('isSecureContext', true)
  vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
  Object.defineProperty(document, 'execCommand', { configurable: true, value: () => true })
  await expect(copyQuotationText('TEST\t5.65')).rejects.toThrow('未复制成功')
})
