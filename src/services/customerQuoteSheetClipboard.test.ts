import { afterEach, expect, it, vi } from 'vitest'
import { copyQuoteSheetData, copyQuotationText } from './customerQuoteSheetClipboard'
import type { CustomerQuoteSheet } from '@/data/customerQuoteSheet'

const sheet: CustomerQuoteSheet = {
  agent: 'Alex', date: '12 Sep 2026', issues: [], quantityLabels: ['1 pc', '2 pcs', '3 pcs', '5 pcs'],
  rows: [{ key: 'a', number: 1, country: 'US', provider: 'Yanwen', shippingTime: '6-12 days', prices: [12.8, null, 28.2, 43.6], sourceDescription: 'private' }],
}
afterEach(() => vi.unstubAllGlobals())
it('writes tabular text with headers and preserves clipboard rejection', async () => {
  vi.stubGlobal('isSecureContext', true)
  const writeText = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', { clipboard: { writeText } })
  await copyQuoteSheetData(sheet)
  expect(writeText.mock.calls[0][0]).toContain('1\t—\t$12.80\t—\t$28.20\t$43.60\tUS\tYanwen\t6-12 workingdays\t1-2 workingdays')
  expect(writeText.mock.calls[0][0]).not.toContain('private')
  writeText.mockRejectedValue(new Error('denied'))
  await expect(copyQuoteSheetData(sheet)).rejects.toThrow('未复制成功')
})
it('reports unsupported clipboard without fallback writes', async () => {
  vi.stubGlobal('isSecureContext', false)
  await expect(copyQuoteSheetData(sheet)).rejects.toThrow('不支持复制数据')
})

it('writes both clipboard formats asynchronously and falls back to the same narrow text when HTML is rejected', async () => {
  vi.stubGlobal('isSecureContext', true)
  vi.stubGlobal('ClipboardItem', class { constructor(public data: Record<string, Blob>) {} })
  const write = vi.fn().mockResolvedValue(undefined), writeText = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', { clipboard: { write, writeText } })
  await copyQuotationText('数量\t运费\n1套\t55.78', '<table>saved</table>')
  const item = write.mock.calls[0]![0][0]
  expect(await item.data['text/html'].text()).toBe('<table>saved</table>')
  expect(await item.data['text/plain'].text()).toBe('数量\t运费\n1套\t55.78')
  expect(writeText).not.toHaveBeenCalled()
  write.mockRejectedValueOnce(new Error('HTML unsupported'))
  await copyQuotationText('same narrow text', '<table>saved</table>')
  expect(writeText).toHaveBeenCalledWith('same narrow text')
})
