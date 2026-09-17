// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { copyQuoteSheetData } from './customerQuoteSheetClipboard'
import { buildCustomerQuoteSheet, newQuoteSheetEdits } from '@/data/customerQuoteSheet'

const sheet = () => buildCustomerQuoteSheet({
  rows: [{ country: 'US', carrier: '4PX', channelKey: 'qc', channelCode: 'qc', ruleId: 1, rule: '', transport: '', eta: '7-12 days', quote1: 25.4, quote2: 48.55, quote3: 71.75, quoteCustom: 118.05 }],
  countries: [], edits: { ...newQuoteSheetEdits('QA'), hiddenColumns: ['shippingTime', 'processingTime'] },
  customQuantity: 5, bundle: true, skus: ['KJ2600787', 'YT2601676'],
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); document.body.innerHTML = ''; delete (document as any).execCommand })

it('copies the exact visible bundle TSV during the click without awaiting Web Locks or the async clipboard', async () => {
  vi.stubGlobal('isSecureContext', true)
  const writeText = vi.fn(() => new Promise<void>(() => {})), request = vi.fn()
  vi.stubGlobal('navigator', { clipboard: { writeText }, locks: { request } })
  const button = document.createElement('button'); document.body.append(button); button.focus()
  let copied = ''
  const exec = vi.fn((command: string) => {
    expect(command).toBe('copy')
    const field = document.activeElement as HTMLTextAreaElement
    copied = field.value.slice(field.selectionStart, field.selectionEnd)
    return true
  })
  Object.defineProperty(document, 'execCommand', { configurable: true, value: exec })
  await copyQuoteSheetData(sheet())
  expect(copied).toBe('No.\tSKU\tCountry\tLogistics Provider\t1 set (USD)\t2 sets (USD)\t3 sets (USD)\t5 sets (USD)\r\n1\tKJ2600787+YT2601676\tUnited States\t4PX\t$25.40\t$48.55\t$71.75\t$118.05')
  expect(writeText).not.toHaveBeenCalled(); expect(request).not.toHaveBeenCalled()
  expect(document.activeElement).toBe(button); expect(document.querySelector('textarea')).toBeNull()
})

it('copies inside an open record dialog and cleans up a rejected command before async fallback', async () => {
  vi.stubGlobal('isSecureContext', true)
  const writeText = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', { clipboard: { writeText } })
  const dialog = document.createElement('dialog'); dialog.open = true; document.body.append(dialog)
  Object.defineProperty(document, 'execCommand', { configurable: true, value: () => {
    expect(dialog.contains(document.activeElement)).toBe(true)
    throw new Error('unsupported command')
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

